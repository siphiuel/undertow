/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers.proxy.mod_cluster;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import io.undertow.UndertowLogger;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.cache.LRUCache;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.util.CopyOnWriteMap;
import io.undertow.util.Headers;
import io.undertow.util.PathMatcher;
import org.xnio.Pool;
import org.xnio.XnioExecutor;
import org.xnio.XnioIoThread;
import org.xnio.ssl.XnioSsl;

/**
 * @author Stuart Douglas
 * @author Emanuel Muckenhuber
 */
class ModClusterContainer {

    // The configured balancers
    private final ConcurrentMap<String, Balancer> balancers = new CopyOnWriteMap<>();

    // The available nodes
    private final ConcurrentMap<String, Node> nodes = new CopyOnWriteMap<>();

    // virtual-host > per context balancing table
    private final ConcurrentMap<String, VirtualHost> hosts = new CopyOnWriteMap<>();

    // Map of removed jvmRoutes to failover domain
    private final LRUCache<String, String> failoverDomains = new LRUCache<>(100, 5 * 60 * 1000);

    // The health check tasks
    private final ConcurrentMap<XnioIoThread, HealthCheckTask> healthChecks = new CopyOnWriteMap<>();
    private final UpdateLoadTask updateLoadTask = new UpdateLoadTask();

    private final XnioSsl xnioSsl;
    private final UndertowClient client;
    private final ProxyClient proxyClient;
    private final ModCluster modCluster;
    private final NodeHealthChecker healthChecker;
    private final long removeBrokenNodesThreshold;

    ModClusterContainer(final ModCluster modCluster, final XnioSsl xnioSsl, final UndertowClient client) {
        this.xnioSsl = xnioSsl;
        this.client = client;
        this.modCluster = modCluster;
        this.healthChecker = modCluster.getHealthChecker();
        this.proxyClient = new ModClusterProxyClient(null, this);
        this.removeBrokenNodesThreshold = removeThreshold(modCluster.getHealthCheckInterval(), modCluster.getRemoveBrokenNodes());
    }

    String getServerID() {
        return modCluster.getServerID();
    }

    UndertowClient getClient() {
        return client;
    }

    XnioSsl getXnioSsl() {
        return xnioSsl;
    }

    /**
     * Get the proxy client.
     *
     * @return the proxy client
     */
    public ProxyClient getProxyClient() {
        return proxyClient;
    }

    Collection<Balancer> getBalancers() {
        return Collections.unmodifiableCollection(balancers.values());
    }

    Collection<Node> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    Node getNode(final String jvmRoute) {
        return nodes.get(jvmRoute);
    }

    /**
     * Get the mod cluster proxy target.
     *
     * @param exchange the http exchange
     * @return
     */
    public ModClusterProxyTarget findTarget(final HttpServerExchange exchange) {
        // There is an option to disable the virtual host check, probably a default virtual host
        final PathMatcher.PathMatch<VirtualHost.HostEntry> entry = mapVirtualHost(exchange);
        if (entry == null) {
            return null;
        }
        for (final Balancer balancer : balancers.values()) {
            final Map<String, Cookie> cookies = exchange.getRequestCookies();
            if (balancer.isStickySession()) {
                if (cookies.containsKey(balancer.getStickySessionCookie())) {
                    final String jvmRoute = getJVMRoute(cookies.get(balancer.getStickySessionCookie()).getValue());
                    if (jvmRoute != null) {
                        return new ModClusterProxyTarget.ExistingSessionTarget(jvmRoute, entry.getValue(), this, balancer.isStickySessionForce());
                    }
                }
                if (exchange.getPathParameters().containsKey(balancer.getStickySessionPath())) {
                    final String id = exchange.getPathParameters().get(balancer.getStickySessionPath()).getFirst();
                    final String jvmRoute = getJVMRoute(id);
                    if (jvmRoute != null) {
                        return new ModClusterProxyTarget.ExistingSessionTarget(jvmRoute, entry.getValue(), this, balancer.isStickySessionForce());
                    }
                }
            }
        }
        return new ModClusterProxyTarget.BasicTarget(entry.getValue(), this);
    }

    /**
     * Register a new node.
     *
     * @param config            the node configuration
     * @param balancerConfig    the balancer configuration
     * @param ioThread          the associated I/O thread
     * @param bufferPool        the buffer pool
     * @return whether the node could be created or not
     */
    public synchronized boolean addNode(final NodeConfig config, final Balancer.BalancerBuilder balancerConfig, final XnioIoThread ioThread, final Pool<ByteBuffer> bufferPool) {

        final String jvmRoute = config.getJvmRoute();
        final Node existing = nodes.get(jvmRoute);
        if (existing != null) {
            if (config.getConnectionURI().equals(existing.getNodeConfig().getConnectionURI())) {
                // TODO better check if they are the same
                existing.resetState();
                return true;
            } else {
                existing.markRemoved();
                removeNode(existing);
                if (!existing.isInErrorState()) {
                    return false; // replies with MNODERM error
                }
            }
        }

        final String balancerRef = config.getBalancer();
        Balancer balancer = balancers.get(balancerRef);
        if (balancer == null) {
            // TODO compare balancer configs, if they are not equal log a warning?
            balancer = balancerConfig.build();
            balancers.put(balancerRef, balancer);
        }
        final Node node = new Node(config, balancer, ioThread, bufferPool, this);
        nodes.put(jvmRoute, node);
        // Schedule the health check
        scheduleHealthCheck(node, ioThread);
        // Reset the load factor periodically
        if (updateLoadTask.cancelKey == null) {
            updateLoadTask.cancelKey = ioThread.executeAtInterval(updateLoadTask, modCluster.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
        }
        // Remove from the failover groups
        failoverDomains.remove(node.getJvmRoute());
        UndertowLogger.ROOT_LOGGER.infof("registering node %s, connection: %s", jvmRoute, config.getConnectionURI());
        return true;
    }

    /**
     * Management command enabling all contexts on the given node.
     *
     * @param jvmRoute    the jvmRoute
     * @return
     */
    public synchronized boolean enableNode(final String jvmRoute) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            for (final Context context : node.getContexts()) {
                context.enable();
            }
            return true;
        }
        return false;
    }

    /**
     * Management command disabling all contexts on the given node.
     *
     * @param jvmRoute    the jvmRoute
     * @return
     */
    public synchronized boolean disableNode(final String jvmRoute) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            for (final Context context : node.getContexts()) {
                context.disable();
            }
            return true;
        }
        return false;
    }

    /**
     * Management command stopping all contexts on the given node.
     *
     * @param jvmRoute    the jvmRoute
     * @return
     */
    public synchronized boolean stopNode(final String jvmRoute) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            for (final Context context : node.getContexts()) {
                context.stop();
            }
            return true;
        }
        return false;
    }

    /**
     * Remove a node.
     *
     * @param jvmRoute the jvmRoute
     * @return the removed node
     */
    public synchronized Node removeNode(final String jvmRoute) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            removeNode(node);
        }
        return node;
    }

    protected void removeNode(final Node node) {
        removeNode(node, false);
    }

    protected synchronized void removeNode(final Node node, boolean onlyInError) {
        if (onlyInError && !node.isInErrorState()) {
            return;
        }
        final String jvmRoute = node.getJvmRoute();
        node.markRemoved();
        if (nodes.remove(jvmRoute, node)) {
            UndertowLogger.ROOT_LOGGER.infof("removing node %s", jvmRoute);
            node.markRemoved();
            // Remove the health check
            removeHealthCheck(node, node.getIoThread());
            // Remove the contexts, if any
            for (final Context context : node.getContexts()) {
                removeContext(context.getPath(), node, context.getVirtualHosts());
            }
            final String domain = node.getNodeConfig().getDomain();
            if (domain != null) {
                failoverDomains.add(node.getJvmRoute(), domain);
            }
            final String balancerName = node.getBalancer().getName();
            for (final Node other : nodes.values()) {
                if (other.getBalancer().getName().equals(balancerName)) {
                    return;
                }
            }
            balancers.remove(balancerName);
        }
        if (nodes.size() == 0) {
            // In case there are no nodes registered unschedule the task
            updateLoadTask.cancelKey.remove();
            updateLoadTask.cancelKey = null;
        }
    }

    /**
     * Register a web context. If the web context already exists, just enable it.
     *
     * @param contextPath the context path
     * @param jvmRoute    the jvmRoute
     * @param aliases     the virtual host aliases
     */
    public synchronized boolean enableContext(final String contextPath, final String jvmRoute, final List<String> aliases) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            Context context = node.getContext(contextPath, aliases);
            if (context == null) {
                context = node.registerContext(contextPath, aliases);
                UndertowLogger.ROOT_LOGGER.infof("registering context %s, for node %s, with aliases %s", contextPath, jvmRoute, aliases);
                for (final String alias : aliases) {
                    VirtualHost virtualHost = hosts.get(alias);
                    if (virtualHost == null) {
                        virtualHost = new VirtualHost();
                        hosts.put(alias, virtualHost);
                    }
                    virtualHost.registerContext(contextPath, jvmRoute, context);
                }
            }
            context.enable();
            return true;
        }
        return false;
    }

    synchronized boolean disableContext(final String contextPath, final String jvmRoute, List<String> aliases) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            node.disableContext(contextPath, aliases);
            return true;
        }
        return false;
    }

    synchronized int stopContext(final String contextPath, final String jvmRoute, List<String> aliases) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            return node.stopContext(contextPath, aliases);
        }
        return -1;
    }

    synchronized boolean removeContext(final String contextPath, final String jvmRoute, List<String> aliases) {
        final Node node = nodes.get(jvmRoute);
        if (node != null) {
            return removeContext(contextPath, node, aliases);
        }
        return false;
    }

    public synchronized boolean removeContext(final String contextPath, final Node node, List<String> aliases) {
        if (node == null) {
            return false;
        }
        final String jvmRoute = node.getJvmRoute();
        UndertowLogger.ROOT_LOGGER.infof("unregistering context '%s' from node '%s'", contextPath, jvmRoute);
        final Context context = node.removeContext(contextPath, aliases);
        if (context == null) {
            return false;
        }
        context.stop();
        for (final String alias : context.getVirtualHosts()) {
            final VirtualHost virtualHost = hosts.get(alias);
            if (virtualHost != null) {
                virtualHost.removeContext(contextPath, jvmRoute, context);
                if (virtualHost.isEmpty()) {
                    hosts.remove(alias);
                }
            }
        }
        return true;
    }

    /**
     * Find a new node handling this request.
     *
     * @param entry the resolved virtual host entry
     * @return the context, {@code null} if not found
     */
    Context findNewNode(final VirtualHost.HostEntry entry) {
        return electNode(entry.getContexts(), false, null);
    }

    /**
     * Try to find a failover node within the same load balancing group.
     *
     * @oaram entry      the resolved virtual host entry
     * @param domain     the load balancing domain, if known
     * @param jvmRoute   the original jvmRoute
     * @return the context, {@code null} if not found
     */
    Context findFailoverNode(final VirtualHost.HostEntry entry, final String domain, final String jvmRoute, final boolean forceStickySession) {
        String failOverDomain = null;
        if (domain == null) {
            final Node node = nodes.get(jvmRoute);
            if (node != null) {
                failOverDomain = node.getNodeConfig().getDomain();
            }
            if (failOverDomain == null) {
                failOverDomain = failoverDomains.get(jvmRoute);
            }
        } else {
            failOverDomain = domain;
        }
        final Collection<Context> contexts = entry.getContexts();
        if (failOverDomain != null) {
            final Context context = electNode(contexts, true, failOverDomain);
            if (context != null) {
                return context;
            }
        }
        if (forceStickySession) {
            return null;
        } else {
            return electNode(contexts, false, null);
        }
    }

    /**
     * Map a request to virtual host.
     *
     * @param exchange    the http exchange
     * @return
     */
    private PathMatcher.PathMatch<VirtualHost.HostEntry> mapVirtualHost(final HttpServerExchange exchange) {
        final String hostName = exchange.getRequestHeaders().getFirst(Headers.HOST);
        if (hostName != null) {
            final String context = exchange.getRelativePath();
            // Remove the port from the host
            int i = hostName.indexOf(":");
            VirtualHost host;
            if (i > 0) {
                host = hosts.get(hostName.substring(0, i));
                if (host == null) {
                    host = hosts.get(hostName);
                }
            } else {
                host = hosts.get(hostName);
            }
            if (host == null) {
                return null;
            }
            PathMatcher.PathMatch<VirtualHost.HostEntry> result =  host.match(context);
            if(result.getValue() == null) {
                return null;
            }
            return result;
        }
        return null;
    }

    static String getJVMRoute(final String sessionId) {
        int index = sessionId.indexOf('.');
        if (index == -1) {
            return null;
        }
        String route = sessionId.substring(index + 1);
        index = route.indexOf('.');
        if (index != -1) {
            route = route.substring(0, index);
        }
        return route;
    }

    static Context electNode(final Iterable<Context> contexts, final boolean existingSession, final String domain) {
        Context elected = null;
        Node candidate = null;
        boolean candidateHotStandby = false;
        for (Context context : contexts) {
            // Skip disabled contexts
            if (context.checkAvailable(existingSession)) {
                final Node node = context.getNode();
                final boolean hotStandby = node.isHotStandby();
                // Check that we only failover in the domain
                if (domain != null && !domain.equals(node.getNodeConfig().getDomain())) {
                    continue;
                }
                if (candidate != null) {
                    // Check if the nodes are in hot-standby
                    if (candidateHotStandby) {
                        if (hotStandby) {
                            if (candidate.getElectedDiff() > node.getElectedDiff()) {
                                candidate = node;
                                elected = context;
                            }
                        } else {
                            candidate = node;
                            elected = context;
                            candidateHotStandby = hotStandby;
                        }
                    } else if (hotStandby) {
                        continue;
                    } else {
                        // Normal election process
                        final int lbStatus1 = candidate.getLoadStatus();
                        final int lbStatus2 = node.getLoadStatus();
                        if (lbStatus1 > lbStatus2) {
                            candidate = node;
                            elected = context;
                            candidateHotStandby = false;
                        }
                    }
                } else {
                    candidate = node;
                    elected = context;
                    candidateHotStandby = hotStandby;
                }
            }
        }
        if (candidate != null) {
            candidate.elected(); // We have a winner!
        }
        return elected;
    }

    void scheduleHealthCheck(final Node node, XnioIoThread ioThread) {
        assert Thread.holdsLock(this);
        HealthCheckTask task = healthChecks.get(ioThread);
        if (task == null) {
            task = new HealthCheckTask(removeBrokenNodesThreshold, healthChecker);
            healthChecks.put(ioThread, task);
            task.cancelKey = ioThread.executeAtInterval(task, modCluster.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
        }
        task.nodes.add(node);
    }

    void removeHealthCheck(final Node node, XnioIoThread ioThread) {
        assert Thread.holdsLock(this);
        final HealthCheckTask task = healthChecks.get(ioThread);
        if (task == null) {
            return;
        }
        task.nodes.remove(node);
        if (task.nodes.size() == 0) {
            healthChecks.remove(ioThread);
            task.cancelKey.remove();
        }
    }

    static long removeThreshold(final long healthChecks, final long removeBrokenNodes) {
        if (healthChecks > 0 && removeBrokenNodes > 0) {
            final long threshold = removeBrokenNodes / healthChecks;
            if (threshold > 1000) {
                return 1000;
            } else if (threshold < 1) {
                return 1;
            } else {
                return threshold;
            }
        } else {
            return -1;
        }
    }

    static class HealthCheckTask implements Runnable {

        private final long threshold;
        private final NodeHealthChecker healthChecker;
        private final ArrayList<Node> nodes = new ArrayList<>();
        private volatile XnioExecutor.Key cancelKey;

        HealthCheckTask(long threshold, NodeHealthChecker healthChecker) {
            this.threshold = threshold;
            this.healthChecker = healthChecker;
        }

        @Override
        public void run() {
            for (final Node node : nodes) {
                node.checkHealth(threshold, healthChecker);
            }
        }
    }

    class UpdateLoadTask implements Runnable {

        private volatile XnioExecutor.Key cancelKey;

        @Override
        public void run() {
            for (final Node node : nodes.values()) {
                node.resetLbStatus();
            }
        }
    }

}
