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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nu.xom.Attribute;
import nu.xom.Document;

/**
 * @author Stuart Douglas
 */
class ModuleParseResult {
    final List<ModuleDependency> dependencies = new ArrayList<>();
    final List<String> resourceRoots = new ArrayList<>();
    final List<ArtifactName> artifacts = new ArrayList<>();
    final Document document;
    ModuleIdentifier identifier;
    ArtifactName versionArtifactName;
    private final Map<String, String> props = new HashMap<>();

    ModuleParseResult(final Document document) {
        this.document = document;
    }

    boolean hasProperties() {
        return !props.isEmpty();
    }

    boolean hasProperty(String name) {
        return props.containsKey(name);
    }

    String getProperty(String name) {
        return props.get(name);
    }

    Map<String, String> getProperties() {
        return props;
    }

    List<ModuleDependency> getDependencies() {
        return dependencies;
    }

    List<String> getResourceRoots() {
        return resourceRoots;
    }

    List<ArtifactName> getArtifacts() {
        return artifacts;
    }

    ModuleIdentifier getIdentifier() {
        return identifier;
    }

    Document getDocument() {
        return document;
    }

    ArtifactName getVersionArtifactName() {
        return versionArtifactName;
    }

    static class ModuleDependency {
        private final ModuleIdentifier moduleId;
        private final boolean optional;
        private final Map<String, String> props;

        ModuleDependency(ModuleIdentifier moduleId, boolean optional, Map<String, String> props) {
            this.moduleId = moduleId;
            this.optional = optional;
            this.props = props;
        }

        ModuleIdentifier getModuleId() {
            return moduleId;
        }

        boolean isOptional() {
            return optional;
        }

        boolean hasProperties() {
            return !props.isEmpty();
        }

        boolean hasProperty(String name) {
            return props.containsKey(name);
        }

        String getProperty(String name) {
            return props.get(name);
        }

        @Override
        public String toString() {
            return "[" + moduleId + (optional ? ",optional=true" : "") + "]";
        }
    }

    static class ArtifactName {

        private final String artifactCoords;
        private final String options;
        private final Attribute attribute;

        ArtifactName(String artifactCoords, String options, final Attribute attribute) {
            this.artifactCoords = artifactCoords;
            this.options = options;
            this.attribute = attribute;
        }

        String getArtifactCoords() {
            return artifactCoords;
        }

        String getOptions() {
            return options;
        }

        Attribute getAttribute() {
            return attribute;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(artifactCoords);
            if (options != null) {
                sb.append('?').append(options);
            }
            return sb.toString();
        }
    }
}
