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
package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import static java.nio.file.FileVisitResult.CONTINUE;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.plugin.CliPlugin;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.spec.PackageSpec;

/**
 *
 * @author jdenise@redhat.com
 */
public class WfCliPlugin implements CliPlugin {
    private static final String MODULE_PATH = "pm/wildfly/module";
    private static final String MODULE_XML = "module.xml";
    private static final String VERSIONS_PATH = "wildfly/artifact-versions.properties";

    @Override
    public CustomPackageContent handlePackageContent(PackageRuntime pkg)
            throws ProvisioningException, ProvisioningDescriptionException, IOException {
        Path modulePath = pkg.getContentDir().getParent().resolve(MODULE_PATH);
        if (Files.exists(modulePath)) {
            Path props = pkg.getFeaturePackRuntime().getResource(VERSIONS_PATH);
            Map<String, String> variables = getVariables(props);
            List<String> artifacts = new ArrayList<>();
            String moduleVersion;
            try {
                moduleVersion = parseModuleDescriptor(variables, pkg.getContentDir(),
                        pkg.getSpec(), artifacts);
            } catch (ParsingException ex) {
                throw new ProvisioningException(ex);
            }
            return new ModuleContent(buildInfo(artifacts, moduleVersion));
        } else {
            return null;
        }
    }

    private class ModuleContent implements CustomPackageContent {

        private final String content;

        private ModuleContent(String content) throws IOException, ProvisioningException {
            this.content = content;
        }

        @Override
        public String getInfo() {
            return content;
        }
    }

    private static String parseModuleDescriptor(Map<String, String> variables,
            Path contentDir, PackageSpec spec, List<String> artifacts) throws IOException, ProvisioningException, ParsingException {
        Path modulePath = contentDir.getParent().resolve(MODULE_PATH);
        List<Path> moduleHolder = new ArrayList<>();
        String moduleVersion = null;
        Files.walkFileTree(modulePath, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attr) {
                if (file.getFileName().toString().equals(MODULE_XML)) {
                    moduleHolder.add(file);
                }
                return CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                    IOException exc) throws IOException {

                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file,
                    IOException exc) {
                return CONTINUE;
            }
        });
        if (moduleHolder.isEmpty()) {
            throw new ProvisioningException("No module descriptor for " + spec.getName());
        }
        Path p = moduleHolder.get(0);
        final Builder builder = new Builder(false);
        final Document document;
        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        }
        final Element rootElement = document.getRootElement();
        final Attribute versionAttribute = rootElement.getAttribute("version");
        if (versionAttribute != null) {
            final String versionExpr = versionAttribute.getValue();
            if (versionExpr.startsWith("${") && versionExpr.endsWith("}")) {
                final String exprBody = versionExpr.substring(2, versionExpr.length() - 1);
                final int optionsIndex = exprBody.indexOf('?');
                final String artifactName;
                if (optionsIndex > 0) {
                    artifactName = exprBody.substring(0, optionsIndex);
                } else {
                    artifactName = exprBody;
                }
                String vers = variables.get(artifactName);
                if (vers != null) {
                    int i = vers.lastIndexOf(":");
                    if (i > 0) {
                        vers = vers.substring(i + 1);
                    }
                    moduleVersion = vers;
                }
            }
        }
        final Element resourcesElement = rootElement.getFirstChildElement("resources", rootElement.getNamespaceURI());
        if (resourcesElement != null) {
            final Elements artfs = resourcesElement.getChildElements("artifact", rootElement.getNamespaceURI());
            final int artifactCount = artfs.size();
            for (int i = 0; i < artifactCount; i++) {
                final Element element = artfs.get(i);
                assert element.getLocalName().equals("artifact");
                final Attribute attribute = element.getAttribute("name");
                final String nameExpr = attribute.getValue();
                if (nameExpr.startsWith("${") && nameExpr.endsWith("}")) {
                    final String exprBody = nameExpr.substring(2, nameExpr.length() - 1);
                    final int optionsIndex = exprBody.indexOf('?');
                    final String artifactName;
                    if (optionsIndex >= 0) {
                        artifactName = exprBody.substring(0, optionsIndex);
                    } else {
                        artifactName = exprBody;
                    }
                    final String resolved = variables.get(artifactName);
                    artifacts.add(resolved);
                }
            }
        }
        return moduleVersion;
    }

    private static String buildInfo(List<String> artifacts, String moduleVersion) {
        StringBuilder builder = new StringBuilder();
        builder.append("Package is a JBOSS module.\n");
        builder.append("Module version : " + (moduleVersion == null ? "none" : moduleVersion) + "\n");
        builder.append("Module artifacts gav\n");
        if (artifacts.isEmpty()) {
            builder.append("NONE\n");
        } else {
            for (String art : artifacts) {
                builder.append(art + "\n");
            }
        }
        return builder.toString();
    }

    private static Map<String, String> getVariables(Path props) throws ProvisioningException, IOException {
        Map<String, String> variables = new HashMap<>();
        if (Files.exists(props)) {
            try (Stream<String> lines = Files.lines(props)) {
                final Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    final String line = iterator.next();
                    final int i = line.indexOf('=');
                    if (i < 0) {
                        throw new ProvisioningException("Failed to locate '=' character in " + line);
                    }
                    variables.put(line.substring(0, i), line.substring(i + 1));
                }
            }
        }
        return variables;
    }
}
