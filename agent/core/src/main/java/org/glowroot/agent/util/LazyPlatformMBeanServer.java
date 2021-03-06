/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.util;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.QueryExp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;

import static java.util.concurrent.TimeUnit.SECONDS;

// this is needed for jboss-modules because calling ManagementFactory.getPlatformMBeanServer()
// before jboss-modules has set up its own logger will trigger the default JUL LogManager to be
// used, and then jboss/wildfly will fail to start
//
// it is also needed for glassfish because it sets the "javax.management.builder.initial" system
// property during startup (instead of from command line)
// see com.sun.enterprise.admin.launcher.JvmOptions.filter() that filters out from command line
// and see com.sun.enterprise.v3.server.SystemTasksImpl.resolveJavaConfig() that adds it back during
// startup
public class LazyPlatformMBeanServer {

    private static final Logger logger = LoggerFactory.getLogger(LazyPlatformMBeanServer.class);

    @GuardedBy("initListeners")
    private final List<InitListener> initListeners = Lists.newArrayList();

    private final boolean waitForMBeanServer;
    private final boolean needsManualPatternMatching;

    private final List<ObjectNamePair> toBeRegistered = Lists.newCopyOnWriteArrayList();
    private final List<ObjectName> toBeUnregistered = Lists.newCopyOnWriteArrayList();

    private volatile @MonotonicNonNull MBeanServer mbeanServer;

    public LazyPlatformMBeanServer() {
        waitForMBeanServer = AppServerDetection.isJBossModules() || AppServerDetection.isOldJBoss()
                || AppServerDetection.isGlassfish();
        needsManualPatternMatching = AppServerDetection.isOldJBoss();
    }

    public void lazyRegisterMBean(Object object, String name) {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(name);
        } catch (MalformedObjectNameException e) {
            logger.warn(e.getMessage(), e);
            return;
        }
        synchronized (initListeners) {
            if (mbeanServer == null) {
                toBeRegistered.add(ImmutableObjectNamePair.of(object, objectName));
                toBeUnregistered.add(objectName);
            } else {
                try {
                    safeRegisterMBean(object, objectName);
                    toBeUnregistered.add(objectName);
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    public void invoke(ObjectName name, String operationName, Object[] params, String[] signature)
            throws Exception {
        ensureInit();
        mbeanServer.invoke(name, operationName, params, signature);
    }

    public Set<ObjectName> queryNames(@Nullable ObjectName name, @Nullable QueryExp query)
            throws InterruptedException {
        ensureInit();
        if (needsManualPatternMatching && name != null && name.isPattern()) {
            return mbeanServer.queryNames(null, new ObjectNamePatternQueryExp(name));
        } else {
            return mbeanServer.queryNames(name, query);
        }
    }

    public MBeanInfo getMBeanInfo(ObjectName name) throws Exception {
        ensureInit();
        return mbeanServer.getMBeanInfo(name);
    }

    public Object getAttribute(ObjectName name, String attribute) throws Exception {
        ensureInit();
        return mbeanServer.getAttribute(name, attribute);
    }

    public void addInitListener(InitListener initListener) {
        synchronized (initListeners) {
            if (mbeanServer == null) {
                initListeners.add(initListener);
            } else {
                try {
                    initListener.postInit(mbeanServer);
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }
    }

    @RequiresNonNull("mbeanServer")
    private void safeRegisterMBean(Object object, ObjectName name) {
        try {
            mbeanServer.registerMBean(object, name);
        } catch (InstanceAlreadyExistsException e) {
            // this happens during unit tests when a non-shared local container is used
            // (so that then there are two local containers in the same jvm)
            //
            // log exception at debug level
            logger.debug(e.getMessage(), e);
        } catch (NotCompliantMBeanException e) {
            if (e.getStackTrace()[0].getClassName()
                    .equals("org.jboss.mx.metadata.MBeanCapability")) {
                // this happens in jboss 4.2.3 because it doesn't know about Java 6 "MXBean"
                // naming convention
                // it's not really that important if glowroot mbeans aren't registered
                logger.debug(e.getMessage(), e);
            } else {
                logger.warn(e.getMessage(), e);
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        ensureInit();
        for (ObjectName name : toBeUnregistered) {
            mbeanServer.unregisterMBean(name);
        }
    }

    @EnsuresNonNull("mbeanServer")
    private void ensureInit() throws InterruptedException {
        if (mbeanServer == null && waitForMBeanServer) {
            waitForMBeanServer(Stopwatch.createUnstarted());
        }
        synchronized (initListeners) {
            if (mbeanServer == null) {
                List<MBeanServer> mbeanServers = MBeanServerFactory.findMBeanServer(null);
                if (mbeanServers.size() == 1) {
                    mbeanServer = mbeanServers.get(0);
                } else {
                    mbeanServer = ManagementFactory.getPlatformMBeanServer();
                }
                for (InitListener initListener : initListeners) {
                    try {
                        initListener.postInit(mbeanServer);
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
                initListeners.clear();
                for (ObjectNamePair objectNamePair : toBeRegistered) {
                    safeRegisterMBean(objectNamePair.object(), objectNamePair.name());
                }
                toBeRegistered.clear();
            }
        }
    }

    @VisibleForTesting
    public static void waitForMBeanServer(Stopwatch stopwatch) throws InterruptedException {
        stopwatch.start();
        while (stopwatch.elapsed(SECONDS) < 60
                && MBeanServerFactory.findMBeanServer(null).isEmpty()) {
            Thread.sleep(100);
        }
        if (MBeanServerFactory.findMBeanServer(null).isEmpty()) {
            logger.error("mbean server was never created by container");
        }
    }

    public interface InitListener {
        void postInit(MBeanServer mbeanServer) throws Exception;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface ObjectNamePair {
        Object object();
        ObjectName name();
    }

    @SuppressWarnings("serial")
    private static class ObjectNamePatternQueryExp implements QueryExp {

        private final ObjectName pattern;

        private ObjectNamePatternQueryExp(ObjectName pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean apply(ObjectName name) {
            return pattern.apply(name);
        }

        @Override
        public void setMBeanServer(MBeanServer s) {}
    }
}
