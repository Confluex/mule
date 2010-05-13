/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.reboot;

import org.mule.api.DefaultMuleException;
import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.config.ConfigurationBuilder;
import org.mule.api.config.ConfigurationException;
import org.mule.config.ExceptionHelper;
import org.mule.config.StartupContext;
import org.mule.config.i18n.CoreMessages;
import org.mule.config.i18n.Message;
import org.mule.context.DefaultMuleContextFactory;
import org.mule.util.ClassUtils;
import org.mule.util.IOUtils;
import org.mule.util.MuleUrlStreamHandlerFactory;
import org.mule.util.PropertiesUtils;
import org.mule.util.StringMessageUtils;
import org.mule.util.SystemUtils;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 */
public class MuleContainer
{

    public static final String CLI_OPTIONS[][] = {
            {"builder", "true", "Configuration Builder Type"},
            {"config", "true", "Configuration File"},
            {"idle", "false", "Whether to run in idle (unconfigured) mode"},
            {"main", "true", "Main Class"},
            {"mode", "true", "Run Mode"},
            {"props", "true", "Startup Properties"},
            {"production", "false", "Production Mode"},
            {"debug", "false", "Configure Mule for JPDA remote debugging."}
    };

    /**
     * Default dev-mode builder with hot-deployment.
     */
    public static final String CLASSNAME_DEV_MODE_CONFIG_BUILDER = "org.mule.config.spring.hotdeploy.ReloadableBuilder";

    /**
     * Don't use a class object so the core doesn't depend on mule-module-spring-config.
     */
    protected static final String CLASSNAME_DEFAULT_CONFIG_BUILDER = "org.mule.config.builders.AutoConfigurationBuilder";

    /**
     * This builder sets up the configuration for an idle Mule node - a node that
     * doesn't do anything initially but is fed configuration during runtime
     */
    protected static final String CLASSNAME_DEFAULT_IDLE_CONFIG_BUILDER = "org.mule.config.builders.MuleIdleConfigurationBuilder";

    /**
     * Required to support the '-config spring' shortcut. Don't use a class object so
     * the core doesn't depend on mule-module-spring. TODO this may not be a problem
     * for Mule 2.x
     */
    protected static final String CLASSNAME_SPRING_CONFIG_BUILDER = "org.mule.config.spring.SpringXmlConfigurationBuilder";

    /**
     * If the annotations module is on the classpath, also enable annotations config builder
     */
    public static final String CLASSNAME_ANNOTATIONS_CONFIG_BUILDER = "org.mule.config.AnnotationsConfigurationBuilder";

    /**
     * logger used by this class
     */
    private static final Log logger = LogFactory.getLog(MuleContainer.class);

    public static final String DEFAULT_CONFIGURATION = "mule-config.xml";

    /**
     * one or more configuration urls or filenames separated by commas
     */
    private String configurationResources = null;

    /**
     * A FQN of the #configBuilder class, required in case MuleContainer is
     * reinitialised.
     */
    private static String configBuilderClassName = null;

    /**
     * A properties file to be read at startup. This can be useful for setting
     * properties which depend on the run-time environment (dev, test, production).
     */
    private static String startupPropertiesFile = null;

    /**
     * The Runtime shutdown thread used to dispose this server
     */
    private static MuleShutdownHook muleShutdownHook;

    /**
     * The MuleContext should contain anything which does not belong in the Registry.
     * There is one MuleContext per Mule instance. Assuming it has been created, a
     * handle to the local MuleContext can be obtained from anywhere by calling
     * MuleContainer.getMuleContext()
     */
    protected static MuleContext muleContext = null;

    /**
     * Application entry point.
     *
     * @param args command-line args
     */
    public static void main(String[] args) throws Exception
    {
        MuleContainer server = new MuleContainer(args);
        server.start(false, true);

    }

    public MuleContainer()
    {
        init(new String[] {});
    }

    public MuleContainer(String configResources)
    {
        // setConfigurationResources(configResources);
        init(new String[] {"-config", configResources});
    }

    /**
     * Configure the server with command-line arguments.
     */
    public MuleContainer(String[] args) throws IllegalArgumentException
    {
        init(args);
    }

    protected void init(String[] args) throws IllegalArgumentException
    {
        Map<String, Object> commandlineOptions;

        try
        {
            commandlineOptions = SystemUtils.getCommandLineOptions(args, CLI_OPTIONS);
        }
        catch (DefaultMuleException me)
        {
            throw new IllegalArgumentException(me.toString());
        }

        // set our own UrlStreamHandlerFactory to become more independent of system
        // properties
        MuleUrlStreamHandlerFactory.installUrlStreamHandlerFactory();

        String config = (String) commandlineOptions.get("config");
        // Try default if no config file was given.
        if (config == null && !commandlineOptions.containsKey("idle"))
        {
            logger.warn("A configuration file was not set, using default: " + DEFAULT_CONFIGURATION);
            // try to load the config as a file as well
            URL configUrl = IOUtils.getResourceAsUrl(DEFAULT_CONFIGURATION, MuleContainer.class, true, false);
            if (configUrl != null)
            {
                config = configUrl.toExternalForm();
            }
            else
            {
                System.out.println(CoreMessages.configNotFoundUsage());
                System.exit(-1);
            }
        }

        if (config != null)
        {
            setConfigurationResources(config);
        }

        // TODO old builders need to be retrofitted to understand the new app/lib
        final String productionMode = (String) commandlineOptions.get("production");
        //if (productionMode == null)
        //{
        try
        {
            setConfigBuilderClassName(CLASSNAME_DEV_MODE_CONFIG_BUILDER);
        }
        catch (Exception e)
        {
            logger.fatal(e);
            final Message message = CoreMessages.failedToLoad("Builder: " + CLASSNAME_DEV_MODE_CONFIG_BUILDER);
            System.err.println(StringMessageUtils.getBoilerPlate("FATAL: " + message.toString()));
            System.exit(1);
        }
        //}


        // Configuration builder
        String cfgBuilderClassName = (String) commandlineOptions.get("builder");

        if (commandlineOptions.containsKey("idle"))
        {
            setConfigurationResources("IDLE");
            cfgBuilderClassName = CLASSNAME_DEFAULT_IDLE_CONFIG_BUILDER;
        }

        // Configuration builder
        if (cfgBuilderClassName != null)
        {
            try
            {
                // Provide a shortcut for Spring: "-builder spring"
                if (cfgBuilderClassName.equalsIgnoreCase("spring"))
                {
                    cfgBuilderClassName = CLASSNAME_SPRING_CONFIG_BUILDER;
                }
                setConfigBuilderClassName(cfgBuilderClassName);
            }
            catch (Exception e)
            {
                logger.fatal(e);
                final Message message = CoreMessages.failedToLoad("Builder: " + cfgBuilderClassName);
                System.err.println(StringMessageUtils.getBoilerPlate("FATAL: " + message.toString()));
                System.exit(1);
            }
        }

        // Startup properties
        String propertiesFile = (String) commandlineOptions.get("props");
        if (propertiesFile != null)
        {
            setStartupPropertiesFile(propertiesFile);
        }

        StartupContext.get().setStartupOptions(commandlineOptions);
    }

    /**
     * Start the mule server
     *
     * @param ownThread determines if the server will run in its own daemon thread or
     *                  the current calling thread
     */
    public void start(boolean ownThread, boolean registerShutdownHook)
    {
        if (registerShutdownHook)
        {
            registerShutdownHook();
        }
        try
        {
            logger.info("Mule Server initializing...");
            initialize();
            logger.info("Mule Server starting...");
            muleContext.start();
        }
        catch (Throwable e)
        {
            shutdown(e);
        }

    }

    /**
     * Sets the configuration builder to use for this server. Note that if a builder
     * is not set and the server's start method is called the default is an instance
     * of <code>SpringXmlConfigurationBuilder</code>.
     *
     * @param builderClassName the configuration builder FQN to use
     * @throws ClassNotFoundException if the class with the given name can not be
     *                                loaded
     */
    public static void setConfigBuilderClassName(String builderClassName) throws ClassNotFoundException
    {
        if (builderClassName != null)
        {
            Class cls = ClassUtils.loadClass(builderClassName, MuleContainer.class);
            if (ConfigurationBuilder.class.isAssignableFrom(cls))
            {
                MuleContainer.configBuilderClassName = builderClassName;
            }
            else
            {
                throw new IllegalArgumentException("Not a usable ConfigurationBuilder class: "
                                                   + builderClassName);
            }
        }
        else
        {
            MuleContainer.configBuilderClassName = null;
        }
    }

    /**
     * Returns the class name of the configuration builder used to create this
     * MuleContainer.
     *
     * @return FQN of the current config builder
     */
    public static String getConfigBuilderClassName()
    {
        if (configBuilderClassName != null)
        {
            return configBuilderClassName;
        }
        else
        {
            return CLASSNAME_DEFAULT_CONFIG_BUILDER;
        }
    }

    /**
     * Initializes this daemon. Derived classes could add some extra behaviour if
     * they wish.
     *
     * @throws Exception if failed to initialize
     */
    public void initialize() throws Exception
    {
        if (configurationResources == null)
        {
            logger.warn("A configuration file was not set, using default: " + DEFAULT_CONFIGURATION);
            configurationResources = DEFAULT_CONFIGURATION;
        }

        ConfigurationBuilder cfgBuilder;

        try
        {
            // create a new ConfigurationBuilder that is disposed afterwards
            cfgBuilder = (ConfigurationBuilder) ClassUtils.instanciateClass(getConfigBuilderClassName(),
                                                                            new Object[] {configurationResources}, MuleContainer.class);
        }
        catch (Exception e)
        {
            throw new ConfigurationException(CoreMessages.failedToLoad(getConfigBuilderClassName()), e);
        }

        if (!cfgBuilder.isConfigured())
        {
            List<ConfigurationBuilder> builders = new ArrayList<ConfigurationBuilder>(2);
            builders.add(cfgBuilder);

            // If the annotations module is on the classpath, add the annotations config builder to the list
            // This will enable annotations config for this instance
            if (ClassUtils.isClassOnPath(CLASSNAME_ANNOTATIONS_CONFIG_BUILDER, getClass()))
            {
                Object configBuilder = ClassUtils.instanciateClass(
                        CLASSNAME_ANNOTATIONS_CONFIG_BUILDER, ClassUtils.NO_ARGS, getClass());
                builders.add((ConfigurationBuilder) configBuilder);
            }

            Properties startupProperties = null;
            if (getStartupPropertiesFile() != null)
            {
                startupProperties = PropertiesUtils.loadProperties(getStartupPropertiesFile(), getClass());
            }
            DefaultMuleContextFactory muleContextFactory = new DefaultMuleContextFactory();
            muleContext = muleContextFactory.createMuleContext(cfgBuilder, startupProperties);
        }
    }

    /**
     * Will shut down the server displaying the cause and time of the shutdown
     *
     * @param e the exception that caused the shutdown
     */
    public void shutdown(Throwable e)
    {
        Message msg = CoreMessages.fatalErrorWhileRunning();
        MuleException muleException = ExceptionHelper.getRootMuleException(e);
        if (muleException != null)
        {
            logger.fatal(muleException.getDetailedMessage());
        }
        else
        {
            logger.fatal(msg.toString() + " " + e.getMessage(), e);
        }
        List<String> msgs = new ArrayList<String>();
        msgs.add(msg.getMessage());
        Throwable root = ExceptionHelper.getRootException(e);
        msgs.add(root.getMessage() + " (" + root.getClass().getName() + ")");
        msgs.add(" ");
        msgs.add(CoreMessages.fatalErrorInShutdown().getMessage());
        String shutdownMessage = StringMessageUtils.getBoilerPlate(msgs, '*', 80);
        logger.fatal(shutdownMessage);

        unregisterShutdownHook();
        doShutdown();
    }

    /**
     * shutdown the server. This just displays the time the server shut down
     */
    public void shutdown()
    {
        logger.info("Mule server shutting down due to normal shutdown request");

        unregisterShutdownHook();
        doShutdown();
    }

    protected void doShutdown()
    {
        if (muleContext != null)
        {
            muleContext.dispose();
            muleContext = null;
        }
        System.exit(0);
    }

    public Log getLogger()
    {
        return logger;
    }

    public void registerShutdownHook()
    {
        if (muleShutdownHook == null)
        {
            muleShutdownHook = new MuleShutdownHook();
        }
        else
        {
            Runtime.getRuntime().removeShutdownHook(muleShutdownHook);
        }
        Runtime.getRuntime().addShutdownHook(muleShutdownHook);
    }

    public void unregisterShutdownHook()
    {
        if (muleShutdownHook != null)
        {
            Runtime.getRuntime().removeShutdownHook(muleShutdownHook);
        }
    }

    // /////////////////////////////////////////////////////////////////
    // Getters and setters
    // /////////////////////////////////////////////////////////////////

    /**
     * Getter for property messengerURL.
     *
     * @return Value of property messengerURL.
     */
    public String getConfigurationResources()
    {
        return configurationResources;
    }

    /**
     * Setter for property configurationResources.
     *
     * @param configurationResources New value of property configurationResources.
     */
    public void setConfigurationResources(String configurationResources)
    {
        this.configurationResources = configurationResources;
    }

    public static String getStartupPropertiesFile()
    {
        return startupPropertiesFile;
    }

    public static void setStartupPropertiesFile(String startupPropertiesFile)
    {
        MuleContainer.startupPropertiesFile = startupPropertiesFile;
    }

    public MuleContext getMuleContext()
    {
        return muleContext;
    }

    /**
     * This class is installed only for MuleContainer running as commandline app. A
     * clean Mule shutdown can be achieved by disposing the
     * {@link org.mule.DefaultMuleContext}.
     */
    private class MuleShutdownHook extends Thread
    {

        public MuleShutdownHook()
        {
            super();
        }

        @Override
        public void run()
        {
            doShutdown();
        }
    }
}

