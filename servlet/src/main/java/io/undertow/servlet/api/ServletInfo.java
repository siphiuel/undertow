/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
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

package io.undertow.servlet.api;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.annotation.MultipartConfig;

import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.util.ConstructorInstanceFactory;

/**
 * @author Stuart Douglas
 */
public class ServletInfo implements Cloneable {

    private final Class<? extends Servlet> servletClass;
    private final String name;

    private final List<String> mappings = new ArrayList<String>();
    private final Map<String, String> initParams = new HashMap<String, String>();
    private final List<SecurityRoleRef> securityRoleRefs = new ArrayList<SecurityRoleRef>();

    private volatile InstanceFactory<? extends Servlet> instanceFactory;
    private volatile String jspFile;
    private volatile Integer loadOnStartup;
    private volatile boolean enabled;
    private volatile boolean asyncSupported;
    private volatile String runAs;
    private volatile MultipartConfigElement multipartConfig;

    public ServletInfo(final String name, final Class<? extends Servlet> servletClass) {
        if (name == null) {
            throw UndertowServletMessages.MESSAGES.paramCannotBeNull("name");
        }
        if (servletClass == null) {
            throw UndertowServletMessages.MESSAGES.paramCannotBeNull("servletClass", "Servlet", name);
        }
        if (!Servlet.class.isAssignableFrom(servletClass)) {
            throw UndertowServletMessages.MESSAGES.servletMustImplementServlet(name, servletClass);
        }
        try {
            final Constructor<? extends Servlet> ctor = servletClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            this.instanceFactory = new ConstructorInstanceFactory(ctor);
            this.name = name;
            this.servletClass = servletClass;
        } catch (NoSuchMethodException e) {
            throw UndertowServletMessages.MESSAGES.componentMustHaveDefaultConstructor("Servlet", servletClass);
        }
    }


    public ServletInfo(final String name, final Class<? extends Servlet> servletClass, final InstanceFactory<? extends Servlet> instanceFactory) {
        if (name == null) {
            throw UndertowServletMessages.MESSAGES.paramCannotBeNull("name");
        }
        if (servletClass == null) {
            throw UndertowServletMessages.MESSAGES.paramCannotBeNull("servletClass", "Servlet", name);
        }
        if (!Servlet.class.isAssignableFrom(servletClass)) {
            throw UndertowServletMessages.MESSAGES.servletMustImplementServlet(name, servletClass);
        }
        this.instanceFactory = instanceFactory;
        this.name = name;
        this.servletClass = servletClass;
    }

    public void validate() {
        //TODO
    }

    @Override
    public ServletInfo clone() {
        ServletInfo info = new ServletInfo(name, servletClass, instanceFactory)
                .setJspFile(jspFile)
                .setLoadOnStartup(loadOnStartup)
                .setEnabled(enabled)
                .setAsyncSupported(asyncSupported)
                .setRunAs(runAs)
                .setMultipartConfig(multipartConfig);
        info.mappings.addAll(mappings);
        info.initParams.putAll(initParams);
        info.securityRoleRefs.addAll(securityRoleRefs);
        return info;
    }

    public Class<? extends Servlet> getServletClass() {
        return servletClass;
    }

    public String getName() {
        return name;
    }

    public void setInstanceFactory(final InstanceFactory<? extends Servlet> instanceFactory) {
        if(instanceFactory == null) {
            throw UndertowServletMessages.MESSAGES.paramCannotBeNull("instanceFactory");
        }
        this.instanceFactory = instanceFactory;
    }

    public InstanceFactory<? extends Servlet> getInstanceFactory() {
        return instanceFactory;
    }

    public List<String> getMappings() {
        return Collections.unmodifiableList(mappings);
    }

    public ServletInfo addMapping(final String mapping) {
        mappings.add(mapping);
        return this;
    }


    public ServletInfo addMappings(final Collection<String> mappings) {
        this.mappings.addAll(mappings);
        return this;
    }


    public ServletInfo addMappings(final String ... mappings) {
        this.mappings.addAll(Arrays.asList(mappings));
        return this;
    }

    public ServletInfo addInitParam(final String name, final String value) {
        initParams.put(name, value);
        return this;
    }


    public Map<String, String> getInitParams() {
        return Collections.unmodifiableMap(initParams);
    }

    public String getJspFile() {
        return jspFile;
    }

    public ServletInfo setJspFile(final String jspFile) {
        this.jspFile = jspFile;
        return this;
    }

    public Integer getLoadOnStartup() {
        return loadOnStartup;
    }

    public ServletInfo setLoadOnStartup(final Integer loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
        return this;
    }

    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    public ServletInfo setAsyncSupported(final boolean asyncSupported) {
        this.asyncSupported = asyncSupported;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ServletInfo setEnabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public String getRunAs() {
        return runAs;
    }

    public ServletInfo setRunAs(final String runAs) {
        this.runAs = runAs;
        return this;
    }

    public MultipartConfigElement getMultipartConfig() {
        return multipartConfig;
    }

    public ServletInfo setMultipartConfig(final MultipartConfigElement multipartConfig) {
        this.multipartConfig = multipartConfig;
        return this;
    }

    public void addSecurityRoleRef(final String role, final String linkedRole) {
        this.securityRoleRefs.add(new SecurityRoleRef(role, linkedRole));
    }

    public List<SecurityRoleRef> getSecurityRoleRefs() {
        return Collections.unmodifiableList(securityRoleRefs);
    }
}
