/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.universe.FeaturePackLocation.ProducerSpec;

/**
 * A module template, built from a module.xml template file.
 *
 * @author jdenise
 */
class ModuleTemplate {

    private final Element rootElement;
    private final Document document;
    private final Path targetPath;
    private final PackageRuntime pkg;
    ModuleTemplate(PackageRuntime pkg, Path moduleTemplate, Path targetPath) throws IOException, ProvisioningDescriptionException {
        final Builder builder = new Builder(false);
        try (BufferedReader reader = Files.newBufferedReader(moduleTemplate, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        rootElement = document.getRootElement();
        this.targetPath = targetPath;
        this.pkg = pkg;
    }

    ProducerSpec getProducerSpec() {
        return pkg.getFeaturePackFPID().getProducer();
    }

    String getName() {
        return rootElement.getAttributeValue("name");
    }

    Element getRootElement() {
        return rootElement;
    }

    Elements getArtifacts() {
        Elements artifacts = null;
        final Element resourcesElement = rootElement.getFirstChildElement("resources", rootElement.getNamespaceURI());
        if (resourcesElement != null) {
            artifacts = resourcesElement.getChildElements("artifact", rootElement.getNamespaceURI());
        }
        return artifacts;
    }

    boolean isModule() {
        return rootElement.getLocalName().equals("module")
                || rootElement.getLocalName().equals("module-alias");
    }

    void store() throws IOException {
        // now serialize the result
        try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
            new Serializer(outputStream).write(document);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
            throw t;
        }
    }
}
