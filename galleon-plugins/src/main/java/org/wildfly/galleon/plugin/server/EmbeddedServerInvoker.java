/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.galleon.plugin.server;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class EmbeddedServerInvoker {
    private final MessageWriter messageWriter;
    private final Path installationDir;
    private final String serverConfig;

    public EmbeddedServerInvoker(MessageWriter messageWriter, Path installationDir, String config) {
        this.messageWriter = messageWriter;
        this.installationDir = installationDir;
        String localConfig = "standalone.xml";
         if(config != null && ! config.isEmpty()) {
             localConfig = config;
         }
        this.serverConfig = localConfig;
    }

    public Path createEmbeddedStandaloneScript(List<String> commands) throws ProvisioningException {
        List<String> allCommands = new ArrayList<>();
        allCommands.add(startEmbeddedServerCommand());
        allCommands.addAll(commands);
        allCommands.add("stop-embedded-server");
        try {
            Path script = Files.createTempFile("", ".cli");
            Files.write(script, allCommands);
            return script;
        } catch (IOException e) {
            throw new ProvisioningException(e);
        }
    }

    public static Path createEmbeddedHostControllerScript(String domainConfig, String hostConfig, List<String> commands) throws ProvisioningException {
        List<String> allCommands = new ArrayList<>();
        allCommands.add(startEmbeddedHostControllerCommand(domainConfig, hostConfig));
        allCommands.addAll(commands);
        allCommands.add("stop-embedded-host-controller");
        try {
            Path script = Files.createTempFile("", ".cli");
            Files.write(script, allCommands);
            return script;
        } catch (IOException e) {
            throw new ProvisioningException(e);
        }
    }

    public String startEmbeddedServerCommand() {
         return String.format("embed-server --admin-only --std-out=echo --server-config=%s", serverConfig);
    }


    public static String startEmbeddedHostControllerCommand(String domainConfig, String hostConfig) {
         String localDomainConfig = "domain.xml";
         if(domainConfig != null && ! domainConfig.isEmpty()) {
             localDomainConfig = domainConfig;
         }
         String localHostConfig = "host.xml";
         if(hostConfig != null && ! hostConfig.isEmpty()) {
             localHostConfig = hostConfig;
         }
         return String.format("embed-host-controller --std-out=echo --domain-config=%s --host-config=%s", localDomainConfig, localHostConfig);
    }

    /**
     * Starts an embedded server to execute commands
     * @param commands the list of commands to execute on the embedded server.
     * @throws ProvisioningException
     */
    public void execute(String ... commands) throws ProvisioningException {
        execute(Arrays.asList(commands));
    }

    /**
     * Starts an embedded server to execute commands
     * @param commands the list of commands to execute on the embedded server.
     * @throws ProvisioningException
     */
    public void execute(List<String> commands) throws ProvisioningException {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        URLClassLoader newCl = ClassLoaderHelper.prepareProvisioningClassLoader(installationDir, originalCl);
        Path script = createEmbeddedStandaloneScript(commands);
        Properties props = System.getProperties();
        try {
            Thread.currentThread().setContextClassLoader(newCl);
            final Class<?> cliScriptRunner = newCl.loadClass("org.jboss.galleon.plugin.wildfly.server.CliScriptRunner");
            final Method execute = cliScriptRunner.getMethod("runCliScript", Path.class, Path.class, MessageWriter.class);
            execute.invoke(null, installationDir, script, messageWriter);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoClassDefFoundError ex) {
            throw new ProvisioningException(ex.getMessage(), ex);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            System.setProperties(props);
            ClassLoaderHelper.close(newCl);
        }
    }
}
