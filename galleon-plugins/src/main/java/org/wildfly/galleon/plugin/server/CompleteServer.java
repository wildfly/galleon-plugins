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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.ProcessHelper;
import org.wildfly.core.launcher.StandaloneCommandBuilder;

/**
 * Helper class to manage a WildFly CompleteServer as an external process.
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class CompleteServer {

    private Process process;
    private ServerOutputConsumer consumer;
    private final Path installDir;
    private final String serverConfig;
//    private final MessageWriter messageWriter;

    public CompleteServer(Path installDir, String serverConfig) {
        this.installDir = installDir;
        this.serverConfig = serverConfig;
//        this.messageWriter = DefaultMessageWriter.getDefaultInstance();
    }

    private Process launchServer(Path installDir, String serverConfig/*, MessageWriter messageWriter*/) throws IOException {
//        messageWriter.verbose("Starting full server for %s using configuration file %s", installDir, serverConfig);
        Launcher launcher = new Launcher(StandaloneCommandBuilder.of(installDir).setServerConfiguration(serverConfig))
                .setRedirectErrorStream(true)
                .addEnvironmentVariable("JBOSS_HOME", installDir.toString());
        return launcher.launch();
    }

    public void startServer() throws IOException {
        this.process = launchServer(installDir, serverConfig/*, messageWriter*/);
        this.consumer = new ServerOutputConsumer(process.getInputStream());
        new Thread(consumer).start();
        waitUntilStarted();
    }

    public void stopServer() {
        try {
            ProcessHelper.destroyProcess(process);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    private void waitUntilStarted() {
        while (!consumer.isStarted()) {
            try {
                Thread.sleep(100);
                if (!process.isAlive() && process.exitValue() != 0) {
                    throw new IllegalStateException(String.format("Error executing synchronization. Couldn't start the installed server at %s", installDir.toAbsolutePath()));
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static class ServerOutputConsumer implements Runnable {

        private static final Pattern JBOSS_7_STARTED_ML = Pattern.compile(".*JBoss AS 7(\\..*)* \\d+ms .*");
        private static final Pattern WILDFLY_8_STARTED_ML = Pattern.compile(".*JBAS015874: WildFly 8(\\..*)* .* started in \\d+ms .*");
        private static final Pattern WILDFLY_9_STARTED_ML = Pattern.compile(".*WFLYSRV0050: WildFly Full \\d+(\\..*)* .* started in \\d+ms .*");
        private static final Pattern WILDFLY_10_STARTED_ML = Pattern.compile(".*WFLYSRV0025: WildFly .* \\d+(\\..*)* .* started in \\d+ms .*");

        private static final Pattern EAP6_STARTED_ML = Pattern.compile(".*JBAS015874: JBoss EAP 6\\.[0-9]?.[0-9]?\\.GA .* \\d+ms .*");
        private static final Pattern EAP7_STARTED_ML = Pattern.compile(".*WFLYSRV0025: JBoss EAP 7\\.[0-9]?.[0-9]?\\.GA .* \\d+ms .*");

        private final BufferedReader source;
        private volatile boolean started = false;

        private ServerOutputConsumer(final InputStream source) {
            this.source = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
            this.started = false;
        }

        @Override
        public void run() {
            try (BufferedReader in = this.source) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!started) {
                        started = isStarted(line);
                    }
                }
            } catch (IOException ignore) {
            }
        }

        private boolean isStarted() {
            return started;
        }

        private boolean isStarted(String line) {
            return ((line.contains("JBoss (MX MicroKernel)") // JBoss 4.x message // NOI18N
                    || line.contains("JBoss (Microcontainer)") // JBoss 5.0 message // NOI18N
                    || line.contains("JBossAS") // JBoss 6.0 message // NOI18N
                    || line.contains("JBoss AS"))// JBoss 7.0 message // NOI18N
                    && (line.contains("Started in")) // NOI18N
                    || line.contains("started in") // NOI18N
                    || line.contains("started (with errors) in")) // JBoss 7 with some errors (include wrong deployments) // NOI18N
                    || JBOSS_7_STARTED_ML.matcher(line).matches()
                    || WILDFLY_8_STARTED_ML.matcher(line).matches()
                    || WILDFLY_9_STARTED_ML.matcher(line).matches()
                    || WILDFLY_10_STARTED_ML.matcher(line).matches()
                    || EAP6_STARTED_ML.matcher(line).matches()
                    || EAP7_STARTED_ML.matcher(line).matches();
        }
    }
}
