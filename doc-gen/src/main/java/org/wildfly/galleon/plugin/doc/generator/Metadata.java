/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public record Metadata(
        String groupId,
        String artifactId,
        String version,
        String name,
        String description,
        List<String> licenses,
        String url,
        @JsonProperty("scm-url") String scmUrl,
        String copyright,
        List<Layer> layers) {

    static Metadata parse(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Metadata metadata = mapper.readValue(file.toFile(), Metadata.class);
        return sortedMetadata(metadata);
    }

    /**
     * Creates a new Metadata instance with all collections sorted alphabetically
     */
    private static Metadata sortedMetadata(Metadata metadata) {
        // Sort layers by name
        List<Layer> sortedLayers = metadata.layers() != null ?
            metadata.layers().stream()
                .map(Metadata::sortedLayer)
                .sorted(Comparator.comparing(Layer::name))
                .toList() : null;

        return new Metadata(
            metadata.groupId(),
            metadata.artifactId(),
            metadata.version(),
            metadata.name(),
            metadata.description(),
            metadata.licenses(),
            metadata.url(),
            metadata.scmUrl(),
            metadata.copyright(),
            sortedLayers
        );
    }

    /**
     * Creates a new Layer instance with all collections sorted alphabetically
     */
    private static Layer sortedLayer(Layer layer) {
        // Sort dependencies by name

        List<LayerDependency> sortedDependencies = layer.dependencies() != null ?
            layer.dependencies().stream()
                .sorted(Comparator.comparing(LayerDependency::name))
                .toList() : null;

        // Sort properties by name
        List<Property> sortedProperties = layer.properties() != null ?
            layer.properties().stream()
                .sorted(Comparator.comparing(Property::name))
                .toList() : null;

        // Sort configurations by address, then by attribute
        List<AttributeConfiguration> sortedConfigurations = layer.configurations() != null ?
            layer.configurations().stream()
                .sorted(Comparator.comparing(AttributeConfiguration::address)
                    .thenComparing(AttributeConfiguration::attribute))
                .toList() : null;

        // Sort packages alphabetically
        Set<String> sortedPackages = layer.packages() != null ?
            layer.packages().stream()
                .sorted().collect(Collectors.toCollection(LinkedHashSet::new)) : null;

        return new Layer(
            layer.name(),
            layer.stability(),
            sortedDependencies,
            layer.managementModel(),
            sortedProperties,
            sortedConfigurations,
            sortedPackages
        );
    }

    public boolean containsLayer(String layerName) {
        return layers().stream()
                .anyMatch(l -> layerName.equals(l.name));
    }
    public record Layer(
            String name,
            String stability,
            List<LayerDependency> dependencies,
            ObjectNode managementModel,
            List<Property> properties,
            List<AttributeConfiguration> configurations,
            Set<String> packages) {

        public String description() {
            return properties.stream()
                    .filter(p -> p.name().equals("org.wildfly.description"))
                    .map(Metadata.Property::value)
                    .findFirst()
                    .orElse(null);
        }

        public String note() {
            return properties.stream()
                    .filter(p -> p.name().equals("org.wildfly.note"))
                    .map(Metadata.Property::value)
                    .findFirst()
                    .orElse(null);
        }

        public String prettyManagementModel() {
            if (managementModel == null) {
                return null;
            }
            ObjectMapper mapper = new ObjectMapper();
            try {
                return mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(managementModel);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }

    public record LayerDependency(
            String name,
            boolean optional
    ) {
    }

    public record Property(
            String name,
            String value
    ) {
    }

    public record AttributeConfiguration(
            //FIXME meh :(
            @JsonProperty("_address")
            String address,
            String attribute,
            @JsonProperty("system-properties")
            Set<String> systemProperties,
            @JsonProperty("environment-variables")
            Set<String> envVars
    ) {

    }
}

