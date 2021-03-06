/* Copyright (c) 2011 Danish Maritime Authority.
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
package dk.dma.commons.app;

import static java.util.Objects.requireNonNull;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.DynamicMBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Service.State;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.name.Names;

import dk.dma.commons.management.Managements;

/**
 * 
 * @author Kasper Nielsen
 */
public abstract class AbstractDmaApplication {

    /** The name of the application. */
    final String applicationName;

    volatile Injector injector;

    private final List<Module> modules = new ArrayList<>();

    final CopyOnWriteArrayList<Service> services = new CopyOnWriteArrayList<>();

    static final Logger LOG = LoggerFactory.getLogger(AbstractDmaApplication.class);

    final CountDownLatch isShutdown = new CountDownLatch(1);

    AbstractDmaApplication() {
        String cliName = CliCommandList.CLI_APP_NAME.get();
        applicationName = cliName == null ? getClass().getSimpleName() : cliName;
    }

    /**
     * Clients should use one of {@link AbstractCommandLineTool}, {@link AbstractSwingApplication} or
     * {@link AbstractWebApplication}.
     * 
     * @param applicationName
     *            the name of the application
     */
    AbstractDmaApplication(String applicationName) {
        this.applicationName = requireNonNull(applicationName);
    }

    protected final void addModule(Module module) {
        modules.add(requireNonNull(module));
    }

    protected void addPropertyFile(String name) {}

    // A required properties
    protected void addPropertyFileOnClasspath(String name) {};

    protected void configure() {}

    private void defaultModule() {
        addModule(new AbstractModule() {

            @Override
            protected void configure() {
                Names.bindProperties(binder(), Collections.singletonMap("app.name", applicationName));
            }
        });
    }

    public final String getApplicationName() {
        return applicationName;
    }

    protected <T extends Service> T start(T service) {
        services.add(requireNonNull(service));
        service.startAsync();
        service.awaitRunning();
        return service;
    }

    protected abstract void run(Injector injector) throws Exception;

    public void sleepUnlessShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        isShutdown.await(timeout, unit);
    }

    void execute() throws Exception {
        defaultModule();
        configure();
        Injector i = Guice.createInjector(modules);
        // Management
        tryManage(this);
        try {
            run(i);
        } finally {
            // Shutdown in reverse order
            Collections.reverse(services);
            for (Service s : services) {
                s.stopAsync();
                s.awaitTerminated();
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down all services");
        // shutdown services in reverse order
        List<Service> list = new ArrayList<>(services);
        Collections.reverse(list);
        for (Service s : list) {
            LOG.info("Trying to shut down " + s.getClass().getName());
            s.stopAsync();
            s.awaitTerminated();
            LOG.info("Succeeded in shutting down " + s.getClass().getName());
        }
        LOG.info("All services was succesfully shutdown");
    }

    void awaitServiceStopped(Service s) throws InterruptedException {
        State st = s.state();
        CountDownLatch cdl = null;
        while (st == State.RUNNING || st == State.NEW) {
            if (cdl != null) {
                cdl.await();
            }
            final CountDownLatch c = cdl = new CountDownLatch(1);
            s.addListener(new Service.Listener() {
                public void terminated(State from) {
                    c.countDown();
                }

                @Override
                public void stopping(State from) {
                    c.countDown();
                }

                @Override
                public void starting() {
                    c.countDown();
                }

                @Override
                public void running() {
                    c.countDown();
                }

                @Override
                public void failed(State from, Throwable failure) {
                    c.countDown();
                }
            }, MoreExecutors.sameThreadExecutor());
        }
    }

    protected void tryManage(Object o) throws Exception {
        DynamicMBean mbean = Managements.tryCreate(this);
        if (mbean != null) {
            MBeanServer mb = ManagementFactory.getPlatformMBeanServer();
            Class<?> c = o.getClass();
            ObjectName objectName = new ObjectName(c.getPackage().getName() + ":type=" + c.getSimpleName());
            mb.registerMBean(mbean, objectName);
        }
    }

    private final Management management = new Management();

    public Management withManagement() {
        return management;
    }

    public static class Management {
        volatile String d;

    }
}
