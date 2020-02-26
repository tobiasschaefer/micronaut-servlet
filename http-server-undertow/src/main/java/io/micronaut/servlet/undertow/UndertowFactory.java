package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.env.Environment;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.exceptions.ServerStartupException;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.*;

import javax.inject.Singleton;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

/**
 * Factory for the undertow server.
 *
 * @author graemerocher
 * @since 1.0
 */
@Factory
public class UndertowFactory extends ServletServerFactory {

    private final UndertowConfiguration configuration;

    /**
     * Default constructor.
     *
     * @param resourceResolver   The resource resolver
     * @param configuration      The configuration
     * @param sslConfiguration   The SSL configuration
     * @param applicationContext The app context
     */
    public UndertowFactory(
            ResourceResolver resourceResolver,
            UndertowConfiguration configuration,
            SslConfiguration sslConfiguration,
            ApplicationContext applicationContext) {
        super(resourceResolver, configuration, sslConfiguration, applicationContext);
        this.configuration = configuration;
    }

    /**
     * The undertow builder bean.
     * @param deploymentInfo The deployment info
     * @return The builder
     */
    @Singleton
    @Primary
    protected Undertow.Builder undertowBuilder(DeploymentInfo deploymentInfo) {
        final Undertow.Builder builder = configuration.getUndertowBuilder();
        int port = getConfiguredPort();
        String host = getConfiguredHost();
        builder.addHttpListener(
                port,
                host
        );

        final String cp = getContextPath();
        final DeploymentManager deploymentManager = Servlets.defaultContainer().addDeployment(deploymentInfo);
        deploymentManager
                .deploy();
        PathHandler path;
        try {
            path = Handlers.path(Handlers.redirect(cp))
                    .addPrefixPath(cp, deploymentManager.start());
        } catch (ServletException e) {
            throw new ServerStartupException("Error starting Undertow server: " + e.getMessage(), e);
        }
        builder.setHandler(path);

        final SslConfiguration sslConfiguration = getSslConfiguration();
        if (sslConfiguration.isEnabled()) {
            final int sslPort = sslConfiguration.getPort();
            build(sslConfiguration).ifPresent(sslContext -> builder.addHttpsListener(
                    sslPort,
                    host,
                    sslContext
            ));

        }
        return builder;
    }

    /**
     * The undertow bean.
     * @param builder The builder
     * @return The undertow bean
     */
    @Singleton
    @Primary
    protected Undertow undertowServer(Undertow.Builder builder) {
        return builder.build();
    }

    /**
     * The deployment info bean.
     * @return The deployment info
     */
    @Singleton
    @Primary
    protected DeploymentInfo deploymentInfo() {
        final String cp = getContextPath();

        ServletInfo servletInfo = Servlets.servlet(
                Environment.MICRONAUT, DefaultMicronautServlet.class, () -> new InstanceHandle<Servlet>() {

                    private DefaultMicronautServlet instance;

                    @Override
                    public Servlet getInstance() {
                        instance = new DefaultMicronautServlet(getApplicationContext());
                        return instance;
                    }

                    @Override
                    public void release() {
                        if (instance != null) {
                            instance.destroy();
                        }
                    }
                }
        );
        servletInfo.setAsyncSupported(true);
        final DeploymentInfo deploymentInfo = Servlets.deployment()
                .setDeploymentName(Environment.MICRONAUT)
                .setClassLoader(getEnvironment().getClassLoader())
                .setContextPath(cp)
                .addServlet(servletInfo.addMapping("/*"));
        configuration.getMultipartConfiguration().ifPresent(deploymentInfo::setDefaultMultipartConfig);
        return deploymentInfo;
    }

}