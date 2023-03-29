/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
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

/**
 * A module template, built from a module.xml template file.
 *
 * @author jdenise
 */
class ModuleTemplate {

    private final Element rootElement;
    private final Document document;
    private final Path targetPath;

    ModuleTemplate(PackageRuntime pkg, Path moduleTemplate, Path targetPath) throws IOException, ProvisioningDescriptionException {
        final Builder builder = new Builder(false);
        try (BufferedReader reader = Files.newBufferedReader(moduleTemplate, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        rootElement = document.getRootElement();
        this.targetPath = targetPath;
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
