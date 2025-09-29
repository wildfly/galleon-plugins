/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.wildfly.galleon.plugin.doc.generator.LogMessageGenerator.exportLogMessages;
import static org.wildfly.galleon.plugin.doc.generator.SimpleLog.SYSTEM_LOG;
import static org.wildfly.galleon.plugin.doc.generator.TemplateUtils.ENGINE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import io.quarkus.qute.Template;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.dmr.ModelNode;
import static org.wildfly.galleon.plugin.doc.generator.FeatureUtils.featureToURL;

public class DocGenerator {

    /**
     * Generate the model reference in the {@code outputDirectory}
     *
     * @param outputDirectory the root directory to generate the model reference
     * @param managementAPIPath the path to the management-api.json file
     * @param featuresPath the path to the features.json file
     * @return true if the model is generated
     */
    public static boolean generateModel(Path outputDirectory, Path managementAPIPath, Path featuresPath) throws IOException {
        return generateModel(SYSTEM_LOG, outputDirectory, managementAPIPath, featuresPath);
    }

    static boolean generateModel(SimpleLog log, Path outputDirectory, Path managementAPIPath, Path featuresPath) throws IOException {
        Path referencePath = outputDirectory.resolve("reference");
        Files.createDirectories(referencePath);

        copyResources(outputDirectory);

        Optional<ModelNode> rootDescription = Optional.empty();
        Optional<Map<String, ModelNode>> featuresDescription = Optional.empty();
        if (Files.exists(managementAPIPath)) {
            try (InputStream in = Files.newInputStream(managementAPIPath)) {
                rootDescription = Optional.of(ModelNode.fromJSONStream(in));
            }
            // The features must exist if the model is generated
            if (!Files.exists(featuresPath)) {
                throw new RuntimeException("Missing features.json file");
            }
            try (InputStream in = Files.newInputStream(featuresPath)) {
                ModelNode featuresNode = ModelNode.fromJSONStream(in);
                featuresDescription = Optional.of(getInstantiableFeatures(featuresNode));
            }
        }

        if (rootDescription.isPresent()) {
            final Map<String, Capability> globalCapabilities = new LinkedHashMap<>(getCapabilityMap(rootDescription.get()));
            generateResource(referencePath, ENGINE.getTemplate("resource"), featuresDescription.get(), rootDescription.get(), globalCapabilities);
            if (log.isDebugEnabled()) {
                log.debug("✏️ Model reference pages generated");
            }
        } else {
            return false;
        }

        return true;
    }

    /**
     * Create a map of resource URL to feature that can be instantiated.
     * Some Galleon features are generated for runtime-only resources that can't
     * be created.
     * @param features The set of features.
     * @return The mapping.
     */
    private static Map<String, ModelNode> getInstantiableFeatures(ModelNode features) {
        Map<String, ModelNode> map = new TreeMap<>();
        for (String name : features.keys()) {
            ModelNode feature = features.get(name);
            String url = featureToURL(name, feature);
            if (url != null) {
                map.put(url, feature);
            }
        }
        return map;
    }

    /**
     * Generate documentation from the feature pack's metadata and model.
     * The documentation is generated in the {@code outputDirectory}. A zip archive
     * that contains the generated documentation is created at {@code docZipArchive}
     */
    public static void generate(SimpleLog log, Path docZipArchive, Path outputDirectory, Path metadataPath, Path managementAPIPath, Path featuresPath, Path localRepositoryPath) throws IOException {
        log.info("🔎 Generating Feature Pack Documentation");

        Path manifestPath = outputDirectory.resolve("META-INF");
        Files.createDirectories(manifestPath);
        Files.copy(metadataPath, manifestPath.resolve("metadata.json"), REPLACE_EXISTING);
        if (Files.exists(managementAPIPath)) {
            Files.copy(managementAPIPath, manifestPath.resolve("management-api.json"), REPLACE_EXISTING);
        }
        if (Files.exists(featuresPath)) {
            Files.copy(featuresPath, manifestPath.resolve("features.json"), REPLACE_EXISTING);
        }

        final boolean hasModel = generateModel(log, outputDirectory, managementAPIPath, featuresPath);

        Metadata metadata = Metadata.parse(metadataPath);

        final boolean hasLogMessages = generateLogMessages(log, outputDirectory, localRepositoryPath, metadataPath.getParent());

        generateIndex(outputDirectory, ENGINE.getTemplate("index"), metadata, hasModel, hasLogMessages);
        if (log.isDebugEnabled()) {
            log.debug("✏️ Index page generated");
        }


        FileUtils.zipDirectory(outputDirectory, docZipArchive);
        log.info("📦 Archive generated at " + docZipArchive);
    }

    static boolean generateLogMessages(SimpleLog log, Path outputDirectory, Path localRepositoryPath, Path artifactListsParentPath) throws IOException {
        log.info("🔎 Exporting log messages from artifact lists...");

        List<LogMessage> messages = new ArrayList<>();

        List<Path> artifactListPaths;
        try(Stream<Path> stream = Files.list(artifactListsParentPath)) {
            artifactListPaths = stream
                    .filter(path -> Files.isRegularFile(path) &&
                            path.getFileName().toString().endsWith("-artifact-list.txt"))
                    .toList();
        }
        for (Path path : artifactListPaths) {
            messages.addAll(exportLogMessages(log, path, localRepositoryPath));
        }

        if (messages.isEmpty()) {
            return false;
        }
        // Sort the log messages by their codes and then ids.
        Map<String, List<LogMessage>> map = new TreeMap<>();
        for (LogMessage msg : messages) {
            if(msg.code().isEmpty()) {
                continue;
            }
            map.computeIfAbsent(msg.code(), (i) -> new ArrayList<>()).add(msg);
        }
        map.forEach((s, msgs) -> Collections.sort(msgs));

        String content = ENGINE.getTemplate("log-message-reference")
                .data("codes", map)
                .render();
        FileUtils.writeToFile(outputDirectory.resolve("log-message-reference.html"), content);
        log.info("✏️ Log Message Reference page generated");
        return true;
    }

    private static void generateIndex(Path outputDirectory, Template index, Metadata metadata, boolean hasManagementAPI, boolean hasLogMessages) throws IOException {
        String content = index
                .data("metadata", metadata)
                .data("hasManagementAPI", hasManagementAPI)
                .data("hasLogMessages", hasLogMessages)
                .render();
        FileUtils.writeToFile(outputDirectory.resolve("index.html"), content);
    }

    private static void generateResource(Path outputDirectory,
                                         Template resourceTemplate,
                                         Map<String, ModelNode> features,
                                         ModelNode model,
                                         Map<String, Capability> globalCapabilities,
                                         PathElement... path) throws IOException {
        final String currentUrl = buildCurrentUrl(path);
        final Resource resource = Resource.fromModelNode(PathAddress.pathAddress(path), model, features.get(currentUrl), globalCapabilities);
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
                    generateResource(outputDirectory, resourceTemplate, features, newModel, globalCapabilities, newPath);

                }
            } else {
                for (Child registration : child.children()) {
                    PathElement[] newPath = addToPath(path, child.name(), registration.name());

                    ModelNode childModel = model.get("children").get(child.name());
                    if (childModel.hasDefined("model-description") && childModel.get("model-description").hasDefined(registration.name())) {
                        ModelNode newModel = childModel.get("model-description").get(registration.name());
                        generateResource(outputDirectory, resourceTemplate, features, newModel, globalCapabilities, newPath);
                    }
                }
            }
        }
    }

    private static Map<String, Capability> getCapabilityMap(ModelNode fullModel) {
        ModelNode capabilitiesModel = fullModel.get("possible-capabilities");
        Map<String, Capability> capabilityMap = new TreeMap<>();
        if (capabilitiesModel.isDefined()) {
            capabilitiesModel.asList().forEach(cap -> {
                Capability capability = Capability.fromModel(cap, Collections.emptyMap(), null);
                capabilityMap.put(capability.name(), capability);
            });
        }
        return capabilityMap;
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

    private static void copyResources(Path outputDirectory) throws IOException {
        try (InputStream cssStream = DocGenerator.class.getResourceAsStream("/styles.css")) {
            if (cssStream != null) {
                Files.copy(cssStream, outputDirectory.resolve("styles.css"), REPLACE_EXISTING);
            }
        }
        try (InputStream cssStream = DocGenerator.class.getResourceAsStream("/scripts.js")) {
            if (cssStream != null) {
                Files.copy(cssStream, outputDirectory.resolve("scripts.js"), REPLACE_EXISTING);
            }
        }
    }
}
