/**
 * Copyright 2005-2016 Restlet
 * <p>
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: Apache 2.0 or or EPL 1.0 (the "Licenses"). You can
 * select the license that you prefer but you may not use this file except in
 * compliance with one of these Licenses.
 * <p>
 * You can obtain a copy of the Apache 2.0 license at
 * http://www.opensource.org/licenses/apache-2.0
 * <p>
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0
 * <p>
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * <p>
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://restlet.com/products/restlet-framework
 * <p>
 * Restlet is a registered trademark of Restlet S.A.S.
 */

package org.restlet.engine;

import org.restlet.Client;
import org.restlet.Context;
import org.restlet.engine.io.IoUtils;
import org.restlet.message.ChallengeScheme;
import org.restlet.message.Method;
import org.restlet.message.Request;
import org.restlet.message.Response;
import org.restlet.util.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Engine supporting the Restlet API. The engine acts as a registry of various {@link Helper} types:
 * {@link org.restlet.engine.security.AuthenticatorHelper} , {@link org.restlet.engine.connector.ClientHelper},
 * {@link org.restlet.engine.converter.ConverterHelper} and {@link org.restlet.engine.connector.ServerHelper} classes.<br>
 *
 * @author Jerome Louvel
 */
public class Engine {

    public static final String DESCRIPTOR = "META-INF/services";

    public static final String DESCRIPTOR_AUTHENTICATOR = "org.restlet.engine.security.AuthenticatorHelper";

    public static final String DESCRIPTOR_AUTHENTICATOR_PATH = DESCRIPTOR + "/"
            + DESCRIPTOR_AUTHENTICATOR;

    public static final String DESCRIPTOR_CLIENT = "org.restlet.engine.ClientHelper";

    public static final String DESCRIPTOR_CLIENT_PATH = DESCRIPTOR + "/"
            + DESCRIPTOR_CLIENT;

    public static final String DESCRIPTOR_CONVERTER = "org.restlet.engine.converter.ConverterHelper";

    public static final String DESCRIPTOR_CONVERTER_PATH = DESCRIPTOR + "/"
            + DESCRIPTOR_CONVERTER;

    public static final String DESCRIPTOR_PROTOCOL = "org.restlet.engine.ProtocolHelper";

    public static final String DESCRIPTOR_PROTOCOL_PATH = DESCRIPTOR + "/"
            + DESCRIPTOR_PROTOCOL;

    public static final String DESCRIPTOR_SERVER = "org.restlet.engine.ServerHelper";

    public static final String DESCRIPTOR_SERVER_PATH = DESCRIPTOR + "/"
            + DESCRIPTOR_SERVER;

    /**
     * The registered engine.
     */
    private static volatile Engine instance = null;

    /**
     * Major version number.
     */
    public static final String MAJOR_NUMBER = "@major-number@";

    /**
     * Minor version number.
     */
    public static final String MINOR_NUMBER = "@minor-number@";

    /**
     * Release number.
     */
    public static final String RELEASE_NUMBER = "@release-type@@release-number@";

    /**
     * Complete version.
     */
    public static final String VERSION = MAJOR_NUMBER + '.' + MINOR_NUMBER
            + RELEASE_NUMBER;

    /**
     * Complete version header.
     */
    public static final String VERSION_HEADER = "Restlet-Framework/" + VERSION;

    /**
     * Clears the current Restlet Engine altogether.
     */
    public static synchronized void clear() {
        instance = null;
    }

    /**
     * Creates a new standalone thread with local Restlet thread variable
     * properly set.
     *
     * @param runnable The runnable task to execute.
     * @param name     The thread name.
     * @return The thread with proper variables ready to run the given runnable
     * task.
     */
    public static Thread createThreadWithLocalVariables(
            final Runnable runnable, String name) {
        // Save the thread local variables
        final org.restlet.Application currentApplication = org.restlet.Application
                .getCurrent();
        final Context currentContext = Context.getCurrent();
        final Integer currentVirtualHost = org.restlet.routing.VirtualHost
                .getCurrent();
        final Response currentResponse = Response.getCurrent();

        Runnable r = () -> {
            // Copy the thread local variables
            Response.setCurrent(currentResponse);
            Context.setCurrent(currentContext);
            org.restlet.routing.VirtualHost.setCurrent(currentVirtualHost);
            org.restlet.Application.setCurrent(currentApplication);

            try {
                // Run the user task
                runnable.run();
            } finally {
                Engine.clearThreadLocalVariables();
            }
        };

        return new Thread(r, name);
    }

    /**
     * Clears the thread local variables set by the Restlet API and engine.
     */
    public static void clearThreadLocalVariables() {
        Response.setCurrent(null);
        Context.setCurrent(null);
        org.restlet.routing.VirtualHost.setCurrent(null);
        org.restlet.Application.setCurrent(null);
    }

    /**
     * Returns the registered Restlet engine.
     *
     * @return The registered Restlet engine.
     */
    public static synchronized Engine getInstance() {
        Engine result = instance;

        if (result == null) {
            result = register();
        }

        return result;
    }


    /**
     * Returns a logger based on the class name of the given object.
     *
     * @param clazz The parent class.
     * @return The logger.
     */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    /**
     * Returns a logger based on the class name of the given object.
     *
     * @param object            The parent object.
     * @param defaultLoggerName The default logger name to use if no one can be inferred from
     *                          the object class.
     * @return The logger.
     */
    public static Logger getLogger(Object object, String defaultLoggerName) {
        if (object != null) {
            return getLogger(object.getClass());
        }
        return getLogger(defaultLoggerName);
    }

    /**
     * Returns a logger based on the given logger name.
     *
     * @param loggerName The logger name.
     * @return The logger.
     */
    public static Logger getLogger(String loggerName) {
        return LoggerFactory.getLogger(loggerName);
    }

    /**
     * Returns the classloader resource for a given name/path.
     *
     * @param name The name/path to lookup.
     * @return The resource URL.
     */
    public static java.net.URL getResource(String name) {
        return getInstance().getClassLoader().getResource(name);
    }

    /**
     * Returns the class object for the given name using the engine classloader.
     *
     * @param className The class name to lookup.
     * @return The class object or null if the class was not found.
     * @see #getClassLoader()
     */
    public static Class<?> loadClass(String className)
            throws ClassNotFoundException {
        return getInstance().getClassLoader().loadClass(className);
    }

    /**
     * Registers a new Restlet Engine.
     *
     * @return The registered engine.
     */
    public static synchronized Engine register() {
        return register(true);
    }

    /**
     * Registers a new Restlet Engine.
     *
     * @param discoverPlugins True if plug-ins should be automatically discovered.
     * @return The registered engine.
     */
    public static synchronized Engine register(boolean discoverPlugins) {
        Engine result = new Engine(discoverPlugins);
        instance = result;
        return result;
    }

    /**
     * Class loader to use for dynamic class loading.
     */
    private volatile ClassLoader classLoader;

    /**
     * List of available authenticator helpers.
     */
    private final List<org.restlet.engine.security.AuthenticatorHelper> registeredAuthenticators;

    /**
     * List of available client connectors.
     */
    private final List<org.restlet.engine.connector.ConnectorHelper<Client>> registeredClients;

    /**
     * List of available converter helpers.
     */
    private final List<org.restlet.engine.converter.ConverterHelper> registeredConverters;

    /**
     * List of available protocol helpers.
     */
    private final List<org.restlet.engine.connector.ProtocolHelper> registeredProtocols;

    /**
     * List of available server connectors.
     */
    private final List<org.restlet.engine.connector.ConnectorHelper<org.restlet.Server>> registeredServers;

    /**
     * User class loader to use for dynamic class loading.
     */
    private volatile ClassLoader userClassLoader;

    /**
     * Constructor that will automatically attempt to discover connectors.
     */
    public Engine() {
        this(true);
    }

    /**
     * Constructor.
     *
     * @param discoverHelpers True if helpers should be automatically discovered.
     */
    public Engine(boolean discoverHelpers) {
        // Prevent engine initialization code from recreating other engines
        instance = this;

        this.classLoader = createClassLoader();
        this.userClassLoader = null;

        this.registeredClients = new CopyOnWriteArrayList<>();
        this.registeredProtocols = new CopyOnWriteArrayList<>();

        this.registeredServers = new CopyOnWriteArrayList<>();
        this.registeredAuthenticators = new CopyOnWriteArrayList<>();
        this.registeredConverters = new CopyOnWriteArrayList<>();

        if (discoverHelpers) {
            try {
                discoverConnectors();
                discoverProtocols();

                discoverAuthenticators();
                discoverConverters();
            } catch (IOException e) {
                Context.getCurrentLogger().warn("An error occurred while discovering the engine helpers.", e);
            }
        }
    }

    /**
     * Creates a new class loader. By default, it returns an instance of
     * {@link org.restlet.engine.util.EngineClassLoader}.
     *
     * @return A new class loader.
     */
    protected ClassLoader createClassLoader() {
        return new org.restlet.engine.util.EngineClassLoader(this);
    }

    /**
     * Creates a new helper for a given client connector.
     *
     * @param client      The client to help.
     * @param helperClass Optional helper class name.
     * @return The new helper.
     */
    @SuppressWarnings("unchecked")
    public org.restlet.engine.connector.ConnectorHelper<Client> createHelper(
            Client client, String helperClass) {
        org.restlet.engine.connector.ConnectorHelper<Client> result = null;

        if (!client.getProtocols().isEmpty()) {
            org.restlet.engine.connector.ConnectorHelper<Client> connector = null;
            for (final Iterator<org.restlet.engine.connector.ConnectorHelper<Client>> iter = getRegisteredClients()
                    .iterator(); (result == null) && iter.hasNext(); ) {
                connector = iter.next();

                if (connector.getProtocols().containsAll(client.getProtocols())) {
                    if ((helperClass == null)
                            || connector.getClass().getCanonicalName()
                            .equals(helperClass)) {
                        try {
                            result = connector.getClass()
                                    .getConstructor(Client.class)
                                    .newInstance(client);
                        } catch (Exception e) {
                            Context.getCurrentLogger().error("Exception during the instantiation of the client connector.", e);
                        }
                    }
                }
            }

            if (result == null) {
                // Couldn't find a matching connector
                StringBuilder sb = new StringBuilder();
                sb.append("No available client connector supports the required protocols: ");

                for (Protocol p : client.getProtocols()) {
                    sb.append("'").append(p.getName()).append("' ");
                }

                sb.append(". Please add the JAR of a matching connector to your classpath.");

                if (Edition.CURRENT == Edition.ANDROID) {
                    sb.append(" Then, register this connector helper manually.");
                }

                Context.getCurrentLogger().warn(sb.toString());
            }
        }

        return result;
    }

    /**
     * Creates a new helper for a given server connector.
     *
     * @param server      The server to help.
     * @param helperClass Optional helper class name.
     * @return The new helper.
     */
    @SuppressWarnings("unchecked")
    public org.restlet.engine.connector.ConnectorHelper<org.restlet.Server> createHelper(
            org.restlet.Server server, String helperClass) {
        org.restlet.engine.connector.ConnectorHelper<org.restlet.Server> result = null;

        if (!server.getProtocols().isEmpty()) {
            org.restlet.engine.connector.ConnectorHelper<org.restlet.Server> connector = null;
            for (final Iterator<org.restlet.engine.connector.ConnectorHelper<org.restlet.Server>> iter = getRegisteredServers()
                    .iterator(); (result == null) && iter.hasNext(); ) {
                connector = iter.next();

                if ((helperClass == null)
                        || connector.getClass().getCanonicalName()
                        .equals(helperClass)) {
                    if (connector.getProtocols().containsAll(
                            server.getProtocols())) {
                        try {
                            result = connector.getClass()
                                    .getConstructor(org.restlet.Server.class)
                                    .newInstance(server);
                        } catch (Exception e) {
                            Context.getCurrentLogger().error("Exception while instantiation the server connector.", e);
                        }
                    }
                }
            }

            if (result == null) {
                // Couldn't find a matching connector
                final StringBuilder sb = new StringBuilder();
                sb.append("No available server connector supports the required protocols: ");

                for (final Protocol p : server.getProtocols()) {
                    sb.append("'").append(p.getName()).append("' ");
                }

                sb.append(". Please add the JAR of a matching connector to your classpath.");

                if (Edition.CURRENT == Edition.ANDROID) {
                    sb.append(" Then, register this connector helper manually.");
                }

                Context.getCurrentLogger().warn(sb.toString());
            }
        }

        return result;
    }

    /**
     * Discovers the authenticator helpers and register the default helpers.
     *
     * @throws IOException
     */
    private void discoverAuthenticators() throws IOException {
        registerHelpers(DESCRIPTOR_AUTHENTICATOR_PATH,
                getRegisteredAuthenticators(), null);
        registerDefaultAuthentications();
    }

    /**
     * Discovers the server and client connectors and register the default
     * connectors.
     *
     * @throws IOException
     */
    private void discoverConnectors() throws IOException {
        registerHelpers(DESCRIPTOR_CLIENT_PATH, getRegisteredClients(), Client.class);
        registerHelpers(DESCRIPTOR_SERVER_PATH, getRegisteredServers(), org.restlet.Server.class);
        registerDefaultConnectors();
    }

    /**
     * Discovers the converter helpers and register the default helpers.
     *
     * @throws IOException
     */
    private void discoverConverters() throws IOException {
        registerHelpers(DESCRIPTOR_CONVERTER_PATH, getRegisteredConverters(), null);
        registerDefaultConverters();
    }

    /**
     * Discovers the protocol helpers and register the default helpers.
     *
     * @throws IOException
     */
    private void discoverProtocols() throws IOException {
        registerHelpers(DESCRIPTOR_PROTOCOL_PATH, getRegisteredProtocols(), null);
        registerDefaultProtocols();
    }

    /**
     * Finds the converter helper supporting the given conversion.
     *
     * @return The converter helper or null.
     */
    public org.restlet.engine.converter.ConverterHelper findHelper() {

        return null;
    }

    /**
     * Finds the authenticator helper supporting the given scheme.
     *
     * @param challengeScheme The challenge scheme to match.
     * @param clientSide      Indicates if client side support is required.
     * @param serverSide      Indicates if server side support is required.
     * @return The authenticator helper or null.
     */
    public org.restlet.engine.security.AuthenticatorHelper findHelper(
            ChallengeScheme challengeScheme, boolean clientSide,
            boolean serverSide) {
        org.restlet.engine.security.AuthenticatorHelper result = null;
        List<org.restlet.engine.security.AuthenticatorHelper> helpers = getRegisteredAuthenticators();
        org.restlet.engine.security.AuthenticatorHelper current;

        for (int i = 0; (result == null) && (i < helpers.size()); i++) {
            current = helpers.get(i);

            if (current.getChallengeScheme().equals(challengeScheme)
                    && ((clientSide && current.isClientSide()) || !clientSide)
                    && ((serverSide && current.isServerSide()) || !serverSide)) {
                result = helpers.get(i);
            }
        }

        return result;
    }

    /**
     * Returns the class loader. It uses the delegation model with the Engine
     * class's class loader as a parent. If this parent doesn't find a class or
     * resource, it then tries the user class loader (via {@link #getUserClassLoader()} and finally the
     * {@link Thread#getContextClassLoader()}.
     *
     * @return The engine class loader.
     * @see org.restlet.engine.util.EngineClassLoader
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Parses a line to extract the provider class name.
     *
     * @param line The line to parse.
     * @return The provider's class name or an empty string.
     */
    private String getProviderClassName(String line) {
        final int index = line.indexOf('#');
        if (index != -1) {
            line = line.substring(0, index);
        }
        return line.trim();
    }

    /**
     * Returns the list of available authentication helpers.
     *
     * @return The list of available authentication helpers.
     */
    public List<org.restlet.engine.security.AuthenticatorHelper> getRegisteredAuthenticators() {
        return this.registeredAuthenticators;
    }

    /**
     * Returns the list of available client connectors.
     *
     * @return The list of available client connectors.
     */
    public List<org.restlet.engine.connector.ConnectorHelper<Client>> getRegisteredClients() {
        return this.registeredClients;
    }

    /**
     * Returns the list of available converters.
     *
     * @return The list of available converters.
     */
    public List<org.restlet.engine.converter.ConverterHelper> getRegisteredConverters() {
        return registeredConverters;
    }

    /**
     * Returns the list of available protocol connectors.
     *
     * @return The list of available protocol connectors.
     */
    public List<org.restlet.engine.connector.ProtocolHelper> getRegisteredProtocols() {
        return this.registeredProtocols;
    }

    /**
     * Returns the list of available server connectors.
     *
     * @return The list of available server connectors.
     */
    public List<org.restlet.engine.connector.ConnectorHelper<org.restlet.Server>> getRegisteredServers() {
        return this.registeredServers;
    }

    /**
     * Returns the class loader specified by the user and that should be used in
     * priority.
     *
     * @return The user class loader
     */
    public ClassLoader getUserClassLoader() {
        return userClassLoader;
    }

    /**
     * Registers the default authentication helpers.
     */
    public void registerDefaultAuthentications() {
        getRegisteredAuthenticators().add(
                new org.restlet.engine.security.HttpBasicHelper());
        getRegisteredAuthenticators().add(
                new org.restlet.engine.security.SmtpPlainHelper());
    }

    /**
     * Registers the default client and server connectors.
     */
    public void registerDefaultConnectors() {
        getRegisteredClients().add(
                new org.restlet.engine.connector.FtpClientHelper(null));
        getRegisteredClients().add(
                new org.restlet.engine.connector.HttpClientHelper(null));
        getRegisteredClients().add(
                new org.restlet.engine.local.ClapClientHelper(null));
        getRegisteredClients().add(
                new org.restlet.engine.local.RiapClientHelper(null));
        getRegisteredServers().add(
                new org.restlet.engine.local.RiapServerHelper(null));

        getRegisteredServers().add(
                new org.restlet.engine.netty.HttpServerHelper(null));

        getRegisteredClients().add(
                new org.restlet.engine.local.FileClientHelper(null));
        getRegisteredClients().add(
                new org.restlet.engine.local.ZipClientHelper(null));
    }

    /**
     * Registers the default converters.
     */
    public void registerDefaultConverters() {
        getRegisteredConverters().add(
                new org.restlet.engine.converter.DefaultConverter());
        getRegisteredConverters().add(
                new org.restlet.engine.converter.StatusInfoHtmlConverter());
    }

    /**
     * Registers the default protocols.
     */
    public void registerDefaultProtocols() {
        getRegisteredProtocols().add(
                new org.restlet.engine.connector.HttpProtocolHelper());
        getRegisteredProtocols().add(
                new org.restlet.engine.connector.WebDavProtocolHelper());
    }

    /**
     * Registers a helper.
     *
     * @param classLoader      The classloader to use.
     * @param provider         Bynary name of the helper's class.
     * @param helpers          The list of helpers to update.
     * @param constructorClass The constructor parameter class to look for.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerHelper(ClassLoader classLoader, String provider,
                               List helpers, Class constructorClass) {
        if ((provider != null) && (!provider.equals(""))) {
            // Instantiate the factory
            try {
                Class providerClass = classLoader.loadClass(provider);

                if (constructorClass == null) {
                    helpers.add(providerClass.newInstance());
                } else {
                    helpers.add(providerClass.getConstructor(constructorClass)
                            .newInstance(constructorClass.cast(null)));
                }
            } catch (Throwable t) {
                Context.getCurrentLogger().info("Unable to register the helper " + provider, t);
            }
        }
    }

    /**
     * Registers a helper.
     *
     * @param classLoader      The classloader to use.
     * @param configUrl        Configuration URL to parse
     * @param helpers          The list of helpers to update.
     * @param constructorClass The constructor parameter class to look for.
     */
    public void registerHelpers(ClassLoader classLoader,
                                java.net.URL configUrl, List<?> helpers, Class<?> constructorClass) {
        try {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(
                        configUrl.openStream(), "utf-8"), IoUtils.BUFFER_SIZE);
                String line = reader.readLine();

                while (line != null) {
                    registerHelper(classLoader, getProviderClassName(line),
                            helpers, constructorClass);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                Context.getCurrentLogger().error("Unable to read the provider descriptor: " + configUrl.toString());
            } finally {
                if (reader != null) {
                    reader.close();
                }
            }
        } catch (IOException ioe) {
            Context.getCurrentLogger().error("Exception while detecting the helpers.", ioe);
        }
    }

    /**
     * Registers a list of helpers.
     *
     * @param descriptorPath   Classpath to the descriptor file.
     * @param helpers          The list of helpers to update.
     * @param constructorClass The constructor parameter class to look for.
     * @throws IOException
     */
    public void registerHelpers(String descriptorPath, List<?> helpers,
                                Class<?> constructorClass) throws IOException {
        ClassLoader classLoader = getClassLoader();
        Enumeration<java.net.URL> configUrls = classLoader.getResources(descriptorPath);

        if (configUrls != null) {
            for (Enumeration<java.net.URL> configEnum = configUrls; configEnum
                    .hasMoreElements(); ) {
                registerHelpers(classLoader, configEnum.nextElement(), helpers,
                        constructorClass);
            }
        }
    }

    /**
     * Registers a factory that is used by the URL class to create the {@link java.net.URLConnection} instances when the
     * {@link java.net.URL#openConnection()} or {@link java.net.URL#openStream()} methods are invoked.
     * <p>
     * The implementation is based on the client dispatcher of the current context, as provided by
     * {@link Context#getCurrent()} method.
     */
    public void registerUrlFactory() {
        // Set up an java.net.URLStreamHandlerFactory for
        // proper creation of java.net.URL instances
        java.net.URL
                .setURLStreamHandlerFactory(new java.net.URLStreamHandlerFactory() {
                    public java.net.URLStreamHandler createURLStreamHandler(
                            String protocol) {
                        final java.net.URLStreamHandler result = new java.net.URLStreamHandler() {

                            @Override
                            protected java.net.URLConnection openConnection(
                                    java.net.URL url) throws IOException {
                                return new java.net.URLConnection(url) {

                                    @Override
                                    public void connect() throws IOException {
                                    }

                                    @Override
                                    public InputStream getInputStream()
                                            throws IOException {
                                        InputStream result = null;

                                        // Retrieve the current context
                                        final Context context = Context
                                                .getCurrent();

                                        if (context != null) {
                                            Response response = context
                                                    .getClientDispatcher()
                                                    .handle(new Request(
                                                            Method.GET,
                                                            this.url.toString()));

                                            if (response.getStatus()
                                                    .isSuccess()) {
                                                result = response.getEntity()
                                                        .getStream();
                                            }
                                        }

                                        return result;
                                    }
                                };
                            }

                        };

                        return result;
                    }

                });
    }

    /**
     * Sets the engine class loader.
     *
     * @param newClassLoader The new user class loader to use.
     */
    public void setClassLoader(ClassLoader newClassLoader) {
        this.classLoader = newClassLoader;
    }

    /**
     * Sets the list of available authentication helpers.
     *
     * @param registeredAuthenticators The list of available authentication helpers.
     */
    public void setRegisteredAuthenticators(
            List<org.restlet.engine.security.AuthenticatorHelper> registeredAuthenticators) {
        synchronized (this.registeredAuthenticators) {
            if (registeredAuthenticators != this.registeredAuthenticators) {
                this.registeredAuthenticators.clear();

                if (registeredAuthenticators != null) {
                    this.registeredAuthenticators
                            .addAll(registeredAuthenticators);
                }
            }
        }
    }

    /**
     * Sets the list of available client helpers.
     *
     * @param registeredClients The list of available client helpers.
     */
    public void setRegisteredClients(
            List<org.restlet.engine.connector.ConnectorHelper<Client>> registeredClients) {
        synchronized (this.registeredClients) {
            if (registeredClients != this.registeredClients) {
                this.registeredClients.clear();

                if (registeredClients != null) {
                    this.registeredClients.addAll(registeredClients);
                }
            }
        }
    }

    /**
     * Sets the list of available converter helpers.
     *
     * @param registeredConverters The list of available converter helpers.
     */
    public void setRegisteredConverters(
            List<org.restlet.engine.converter.ConverterHelper> registeredConverters) {
        synchronized (this.registeredConverters) {
            if (registeredConverters != this.registeredConverters) {
                this.registeredConverters.clear();

                if (registeredConverters != null) {
                    this.registeredConverters.addAll(registeredConverters);
                }
            }
        }
    }

    /**
     * Sets the list of available protocol helpers.
     *
     * @param registeredProtocols The list of available protocol helpers.
     */
    public void setRegisteredProtocols(
            List<org.restlet.engine.connector.ProtocolHelper> registeredProtocols) {
        synchronized (this.registeredProtocols) {
            if (registeredProtocols != this.registeredProtocols) {
                this.registeredProtocols.clear();

                if (registeredProtocols != null) {
                    this.registeredProtocols.addAll(registeredProtocols);
                }
            }
        }
    }

    /**
     * Sets the list of available server helpers.
     *
     * @param registeredServers The list of available server helpers.
     */
    public void setRegisteredServers(
            List<org.restlet.engine.connector.ConnectorHelper<org.restlet.Server>> registeredServers) {
        synchronized (this.registeredServers) {
            if (registeredServers != this.registeredServers) {
                this.registeredServers.clear();

                if (registeredServers != null) {
                    this.registeredServers.addAll(registeredServers);
                }
            }
        }
    }

    /**
     * Sets the user class loader that should used in priority.
     *
     * @param newClassLoader The new user class loader to use.
     */
    public void setUserClassLoader(ClassLoader newClassLoader) {
        this.userClassLoader = newClassLoader;
    }

}
