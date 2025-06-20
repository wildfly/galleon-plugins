/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import org.apache.maven.plugin.logging.Log;
import org.jboss.dmr.ModelNode;

public class DocGenerator {

    /**
     * Generate documentation from the feature pack's metadata and model.
     * The documentation is generated in the {@code outputDirectory}. A zip archive
     * that contains the generated documentation is created at {@code docZipArchive}
     */
    public static void generate(Log log, Path docZipArchive, Path outputDirectory, Path metadataPath, Path modelPath) throws IOException {
        log.info("🔎 Generating Feature Pack Documentation");

        Path referencePath = outputDirectory.resolve("reference");
        Files.createDirectories(referencePath);

        Path manifestPath = outputDirectory.resolve("META-INF");
        Files.createDirectories(manifestPath);
        Files.copy(metadataPath, manifestPath.resolve("metadata.json"), REPLACE_EXISTING);

        Metadata metadata = Metadata.parse(metadataPath);

        Optional<ModelNode> rootDescription = Optional.empty();
        if (Files.exists(modelPath)) {
            Files.copy(modelPath, manifestPath.resolve("model.json"), REPLACE_EXISTING);
            try (InputStream in = Files.newInputStream(modelPath)) {
                rootDescription = Optional.of(ModelNode.fromJSONStream(in));
            }
        }

        Engine engine = TemplateUtils.engine();

        generateIndex(outputDirectory, engine.getTemplate("index"), metadata, rootDescription.isPresent());
        if (log.isDebugEnabled()) {
            log.debug("✏️ Index page generated");
        }

        if (rootDescription.isPresent()) {
            generateResource(referencePath, engine.getTemplate("resource"), rootDescription.get());
            if (log.isDebugEnabled()) {
                log.debug("✏️ Model reference pages generated");
            }
        }

        FileUtils.zipDirectory(outputDirectory, docZipArchive);
        log.info("📦 Archive generated at " + docZipArchive);
    }

    private static void generateIndex(Path outputDirectory, Template index, Metadata metadata, boolean hasModel) throws IOException {
        String content = index
                .data("metadata", metadata)
                .data("hasModel", hasModel)
                .render();
        FileUtils.writeToFile(outputDirectory.resolve("index.html"), content);
    }
    private static void generateResource(Path outputDirectory,
                                         Template resourceTemplate,
                                         ModelNode model, PathElement... path) throws IOException {
        final Resource resource = Resource.fromModelNode(model, Collections.emptyMap());
        final String currentUrl = buildCurrentUrl(path);
        final String relativePathToContextRoot = createRelativePathToContextRoot(currentUrl);

        String content = resourceTemplate
                .data("currentUrl", currentUrl)
                .data("resource", resource)
                .data("breadcrumbs", Breadcrumb.build(path))
                .data("relativePathToContextRoot", relativePathToContextRoot)
                .render();
        Path dir = outputDirectory.resolve(currentUrl).normalize();
        FileUtils.writeToFile(dir.resolve("index.html"), content);

        for (Child child : resource.children()) {
            if (child.children().isEmpty()) {
                PathElement[] newPath = addToPath(path, child.name(), "*");
                ModelNode childModel = model.get("children").get(child.name());
                if (childModel.hasDefined("model-description")) {
                    ModelNode newModel = childModel.get("model-description").get("*");
                    if (newModel.hasDefined("operations")) {
                        newModel.get("operations");
                    }
                    generateResource(outputDirectory, resourceTemplate, newModel, newPath);
                }
            } else {
                for (Child registration : child.children()) {
                    PathElement[] newPath = addToPath(path, child.name(), registration.name());

                    ModelNode childModel = model.get("children").get(child.name());
                    if (childModel.hasDefined("model-description") && childModel.get("model-description").hasDefined(registration.name())) {
                        ModelNode newModel = childModel.get("model-description").get(registration.name());
                        generateResource(outputDirectory, resourceTemplate, newModel, newPath);
                    }
                }
            }
        }
    }

    static String buildCurrentUrl(final PathElement... path) {
        StringBuilder sb = new StringBuilder();
        for (PathElement i : path) {
            if (!sb.toString().isEmpty()) {
                sb.append('/');
            }
            sb.append(i.getKey());
            if (!i.isWildcard()) {
                sb.append('/');
                sb.append(i.getValue());
            }
        }
        return sb.toString();
    }

    static String createRelativePathToContextRoot(String relativeUrl) {
        StringBuilder sb = new StringBuilder();
        int length = relativeUrl.isEmpty() ? 0 : relativeUrl.split("/").length;
        sb.append("../".repeat(length));
        return sb.toString();
    }

    private static PathElement[] addToPath(PathElement[] path, final String key, final String value) {
        PathElement[] newPath = new PathElement[path.length + 1];
        System.arraycopy(path, 0, newPath, 0, path.length);
        newPath[path.length] = new PathElement(key, value);
        return newPath;
    }
}
