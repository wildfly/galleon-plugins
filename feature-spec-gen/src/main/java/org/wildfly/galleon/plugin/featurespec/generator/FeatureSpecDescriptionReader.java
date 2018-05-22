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

package org.wildfly.galleon.plugin.featurespec.generator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.StringUtils;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.core.embedded.HostController;
import org.wildfly.core.embedded.StandaloneServer;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureSpecDescriptionReader {

    public static void main(String... args) throws Exception {
        if(args.length != 3) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, Arrays.asList(args));
            throw new IllegalArgumentException("Expected 2 arguments but for " + buf);
        }
        final ModelNode result;
        if(WfConstants.STANDALONE.equals(args[1])) {
            result = readStandaloneFeatures(args[0]);
        } else if(WfConstants.DOMAIN.equals(args[1])) {
            result = readDomainFeatures(args[0]);
        } else {
            throw new IllegalArgumentException("Unexpected controller type " + args[1]);
        }
        try(BufferedWriter writer = Files.newBufferedWriter(Paths.get(args[2]))) {
            writer.write(result.asString());
        }
    }

    public static ModelNode readStandalone(String jbossHome, boolean forkEmbedded) throws ProvisioningException {
        if(forkEmbedded) {
            return forkEmbedded(jbossHome, "standalone");
        } else {
            return readStandaloneFeatures(jbossHome);
        }
    }

    public static ModelNode readDomain(String jbossHome, boolean forkEmbedded) throws ProvisioningException {
        if(forkEmbedded) {
            return forkEmbedded(jbossHome, "domain");
        } else {
            return readDomainFeatures(jbossHome);
        }
    }

    private static ModelNode readStandaloneFeatures(String jbossHome) throws ProvisioningException {
        final StandaloneServer server = EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, new String[] {"--admin-only"});
        try {
            server.start();
            return readFeatures(server.getModelControllerClient());
        } catch (EmbeddedProcessStartException ex) {
            throw new ProvisioningException("Failed to embed server", ex);
        } finally {
            server.stop();
        }
    }

    private static ModelNode readDomainFeatures(String jbossHome) throws ProvisioningException {
        final HostController server = EmbeddedProcessFactory.createHostController(jbossHome, null, null, new String[] {"--admin-only"});
        try {
            server.start();
            return readFeatures(server.getModelControllerClient());
        } catch (EmbeddedProcessStartException ex) {
            throw new ProvisioningException("Failed to embed host controller", ex);
        } finally {
            server.stop();
        }
    }

    private static ModelNode readFeatures(ModelControllerClient client) throws ProvisioningException {
        final ModelNode op = Operations.createOperation("read-feature-description");
        op.get("recursive").set(true);
        final ModelNode result;
        try {
            result = client.execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read feature descriptions", e);
        }
        if(!Operations.isSuccessfulOutcome(result)) {
            throw new ProvisioningException(Operations.getFailureDescription(result).asString());
        }
        if (result.hasDefined("result")) {
            return result.require("result").require("feature");
        } else {
            throw new ProvisioningException("The outcome does not include 'result': " + result.asString());
        }
    }

    private static ModelNode forkEmbedded(String jbossHome, String controllerType) throws ProvisioningException {
        final StringBuilder cp = new StringBuilder();
        collectCpUrls(System.getProperty("java.home"), Thread.currentThread().getContextClassLoader(), cp);

        File descrFile;
        try {
            descrFile = File.createTempFile("rfd", controllerType);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to create a temporary file", e);
        }

        final String[] commands = new String[] {"java",
                "-cp", cp.toString(),
                FeatureSpecDescriptionReader.class.getName(),
                jbossHome,
                controllerType,
                descrFile.getAbsolutePath()};
        Process p;
        try {
            p = new ProcessBuilder(Arrays.asList(commands)).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to start a feature spec reading process", e);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringWriter writer = new StringWriter();
                BufferedWriter bw = new BufferedWriter(writer);) {
            String line = reader.readLine();
            while (line != null) {
                bw.write(line);
                bw.newLine();
                line = reader.readLine();
            }
            if (p.isAlive()) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (p.exitValue() != 0) {
                System.err.println(writer.getBuffer().toString());
                throw new RuntimeException("Process has failed");
            }
        } catch (IOException e) {
            throw new ProvisioningException("Process has failed", e);
        }

        try (InputStream is = new FileInputStream(descrFile)) {
            return ModelNode.fromStream(is);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read resulting file " + descrFile, e);
        }
    }

    private static void collectCpUrls(String javaHome, ClassLoader cl, StringBuilder buf) {
        final ClassLoader parentCl = cl.getParent();
        if(parentCl != null) {
            collectCpUrls(javaHome, cl.getParent(), buf);
        }
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader)cl).getURLs()) {
                final String file = url.getFile();
                if(file.startsWith(javaHome)) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(file);
            }
        }
    }
}
