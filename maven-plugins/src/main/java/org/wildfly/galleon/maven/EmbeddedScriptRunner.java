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
package org.wildfly.galleon.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import javax.xml.stream.XMLStreamException;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.core.embedded.HostController;
import org.wildfly.core.embedded.StandaloneServer;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class EmbeddedScriptRunner {

   public static void exportStandalone(Path wildfly, Path outputDir, Map<String, String> inheritedFeatures, Properties props) throws IOException, ProvisioningException {
        StandaloneServer server = EmbeddedProcessFactory.createStandaloneServer(wildfly.toAbsolutePath().toString(), null, null, new String[]{"--admin-only"});
        try {
            server.start();
            try (ModelControllerClient client = server.getModelControllerClient()) {
                exportFeatures(client, outputDir, inheritedFeatures);
            } catch (XMLStreamException | ProvisioningDescriptionException ex) {
                throw new ProvisioningException(ex.getMessage(), ex);
            }
        } catch (EmbeddedProcessStartException ex) {
            throw new IOException(ex.getMessage(), ex);
        } finally {
            server.stop();
            clearXMLConfiguration(props);
        }
    }

   public static void exportDomain(Path wildfly, Path outputDir, Map<String, String> inheritedFeatures, Properties props) throws IOException, ProvisioningException {
        HostController host = EmbeddedProcessFactory.createHostController(wildfly.toAbsolutePath().toString(), null, null, new String[]{"--admin-only"});
        try {
            host.start();
            try (ModelControllerClient client = host.getModelControllerClient()) {
                exportFeatures(client, outputDir, inheritedFeatures);
            } catch (XMLStreamException | ProvisioningDescriptionException ex) {
                throw new ProvisioningException(ex.getMessage(), ex);
            }
        } catch (EmbeddedProcessStartException ex) {
            throw new IOException(ex.getMessage(), ex);
        } finally {
            host.stop();
            clearXMLConfiguration(props);
        }
    }

    private static void exportFeatures(ModelControllerClient client, Path outputDir, Map<String, String> inheritedFeatures) throws IOException, ProvisioningDescriptionException, XMLStreamException {
        ModelNode address = new ModelNode().setEmptyList();
        ModelNode op = Operations.createOperation("read-feature", address);
        op.get("recursive").set(true);
        ModelNode result = client.execute(op);
        FeatureSpecExporter.export(result, outputDir, inheritedFeatures);
    }

   public static ModelNode readStandaloneFeatures(Path wildfly, Properties props) throws IOException, ProvisioningException {
        StandaloneServer server = EmbeddedProcessFactory.createStandaloneServer(wildfly.toAbsolutePath().toString(), null, null, new String[]{"--admin-only"});
        try {
            server.start();
            try (ModelControllerClient client = server.getModelControllerClient()) {
                return readFeatures(client);
            } catch (XMLStreamException | ProvisioningDescriptionException ex) {
                throw new ProvisioningException(ex.getMessage(), ex);
            }
        } catch (EmbeddedProcessStartException ex) {
            throw new IOException(ex.getMessage(), ex);
        } finally {
            server.stop();
            clearXMLConfiguration(props);
        }
    }

   public static ModelNode readDomainFeatures(Path wildfly, Properties props) throws IOException, ProvisioningException {
        HostController host = EmbeddedProcessFactory.createHostController(wildfly.toAbsolutePath().toString(), null, null, new String[]{"--admin-only"});
        try {
            host.start();
            try (ModelControllerClient client = host.getModelControllerClient()) {
                return readFeatures(client);
            } catch (XMLStreamException | ProvisioningDescriptionException ex) {
                throw new ProvisioningException(ex.getMessage(), ex);
            }
        } catch (EmbeddedProcessStartException ex) {
            throw new IOException(ex.getMessage(), ex);
        } finally {
            host.stop();
            clearXMLConfiguration(props);
        }
    }

    private static ModelNode readFeatures(ModelControllerClient client) throws IOException, ProvisioningDescriptionException, XMLStreamException {
        ModelNode address = new ModelNode().setEmptyList();
        ModelNode op = Operations.createOperation("read-feature", address);
        op.get("recursive").set(true);
        ModelNode result = client.execute(op);
        checkOutcome(result);
        if (result.hasDefined("result")) {
            return result.require("result");
        }
        return result;
    }

    private static void checkOutcome(final ModelNode result) throws ProvisioningDescriptionException {
        if (!result.get("outcome").asString().equals("success")) {
            if (result.hasDefined("failure-description")) {
                throw new ProvisioningDescriptionException(result.get("failure-description").asString());
            }
            throw new ProvisioningDescriptionException("Error executing operation " + result.asString());
        }
    }

    private static void clearXMLConfiguration(Properties props) {
        clearProperty(props, "javax.xml.parsers.DocumentBuilderFactory");
        clearProperty(props, "javax.xml.parsers.SAXParserFactory");
        clearProperty(props, "javax.xml.transform.TransformerFactory");
        clearProperty(props, "javax.xml.xpath.XPathFactory");
        clearProperty(props, "javax.xml.stream.XMLEventFactory");
        clearProperty(props, "javax.xml.stream.XMLInputFactory");
        clearProperty(props, "javax.xml.stream.XMLOutputFactory");
        clearProperty(props, "javax.xml.datatype.DatatypeFactory");
        clearProperty(props, "javax.xml.validation.SchemaFactory");
        clearProperty(props, "org.xml.sax.driver");
    }

    private static void clearProperty(Properties props, String name) {
        if (props.containsKey(name)) {
            System.setProperty(name, props.getProperty(name));
        } else {
            System.clearProperty(name);
        }
    }
}
