/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record Metadata(
        String groupId,
        String artifactId,
        String version,
        String name,
        String description,
        List<String> licenses,
        String url,
        @JsonProperty("scm-url") String scmUrl,
        List<Layer> layers) {

    static Metadata parse(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file.toFile(), Metadata.class);
    }

    public record Layer(
            String name,
            List<LayerDependency> dependencies,
            ObjectNode managementModel,
            List<Property> properties,
            List<AttributeConfiguration> configurations,
            List<String> packages) {
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

