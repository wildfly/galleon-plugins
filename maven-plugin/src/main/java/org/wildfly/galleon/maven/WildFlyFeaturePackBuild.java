/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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


import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.spec.CapabilitySpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.maven.build.tasks.ResourcesTask;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.ArtifactCoords.Gav;

/**
 * Representation of the feature pack build config
 *
 * @author Stuart Douglas
 * @author Alexey Loubyansky
 */
public class WildFlyFeaturePackBuild {

    public static class Builder {

        private FeaturePackLocation producer;
        private Map<Gav, FeaturePackDependencySpec> dependencies = Collections.emptyMap();
        private Set<String> schemaGroups = Collections.emptySet();
        private Set<String> defaultPackages = Collections.emptySet();
        private List<ConfigModel> configs = Collections.emptyList();
        private boolean includePlugin = true;
        private Map<String, ArtifactCoords> plugins = Collections.emptyMap();
        private List<ResourcesTask> resourcesTasks = Collections.emptyList();
        private List<String> standaloneExtensions = Collections.emptyList();
        private List<String> domainExtensions = Collections.emptyList();
        private List<String> hostExtensions = Collections.emptyList();
        private Set<String> systemPaths = Collections.emptySet();
        private String configStabilityLevel;
        private String packageStabilityLevel;
        private String minimumStabilityLevel;
        private String stabilityLevel;

        private Builder() {
        }

        public Builder setProducer(FeaturePackLocation producer) {
            this.producer = producer;
            return this;
        }

        public Builder addDefaultPackage(String packageName) {
            defaultPackages = CollectionUtils.add(defaultPackages, packageName);
            return this;
        }

        public Builder addDependency(Gav gav, FeaturePackDependencySpec dependency) {
            dependencies = CollectionUtils.putLinked(dependencies, gav, dependency);
            return this;
        }

        public Builder addSchemaGroup(String groupId) {
            schemaGroups = CollectionUtils.add(schemaGroups, groupId);
            return this;
        }

        public Builder addConfig(ConfigModel config) {
            configs = CollectionUtils.add(configs, config);
            return this;
        }

        public Builder setIncludePlugin(boolean includePlugin) {
            this.includePlugin = includePlugin;
            return this;
        }

        public Builder addPlugin(String id, ArtifactCoords coords) {
            this.plugins = CollectionUtils.put(plugins, id, coords);
            return this;
        }

        public Builder addResourcesTask(ResourcesTask task) {
            this.resourcesTasks = CollectionUtils.add(resourcesTasks, task);
            return this;
        }

        public Builder addStandaloneExtension(String extension) {
            standaloneExtensions = CollectionUtils.add(standaloneExtensions, extension);
            return this;
        }

        public Builder addDomainExtension(String extension) {
            domainExtensions = CollectionUtils.add(domainExtensions, extension);
            return this;
        }

        public Builder addHostExtension(String extension) {
            hostExtensions = CollectionUtils.add(hostExtensions, extension);
            return this;
        }

        public Builder addSystemPath(String systemPath) {
            systemPaths = CollectionUtils.add(systemPaths, systemPath);
            return this;
        }

        public Builder setConfigStabilityLevel(String stabilityLevel) {
            configStabilityLevel = stabilityLevel;
            return this;
        }

        public Builder setPackageStabilityLevel(String stabilityLevel) {
            packageStabilityLevel = stabilityLevel;
            return this;
        }

        public Builder setMinimumStabilityLevel(String stabilityLevel) {
            minimumStabilityLevel = stabilityLevel;
            return this;
        }

        public Builder setStabilityLevel(String stabilityLevel) {
            this.stabilityLevel = stabilityLevel;
            return this;
        }

        public WildFlyFeaturePackBuild build() {
            return new WildFlyFeaturePackBuild(this);
        }

        void providesCapability(CapabilitySpec cap) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final FeaturePackLocation producer;
    private final Map<Gav, FeaturePackDependencySpec> dependencies;
    private final Set<String> schemaGroups;
    private final Set<String> defaultPackages;
    private final List<ConfigModel> configs;
    private final boolean includePlugin;
    private final Map<String, ArtifactCoords> plugins;
    private final List<ResourcesTask> resourcesTasks;
    private final List<String> standaloneExtensions;
    private final List<String> domainExtensions;
    private final List<String> hostExtensions;
    private final Set<String> systemPaths;
    private final String configStabilityLevel;
    private final String packageStabilityLevel;
    private final String minimumStabilityLevel;
    private final String stabilityLevel;

    private WildFlyFeaturePackBuild(Builder builder) {
        this.producer = builder.producer;
        this.dependencies = CollectionUtils.unmodifiable(builder.dependencies);
        this.schemaGroups = CollectionUtils.unmodifiable(builder.schemaGroups);
        this.defaultPackages = CollectionUtils.unmodifiable(builder.defaultPackages);
        this.configs = CollectionUtils.unmodifiable(builder.configs);
        this.includePlugin = builder.includePlugin;
        this.plugins = CollectionUtils.unmodifiable(builder.plugins);
        this.resourcesTasks = CollectionUtils.unmodifiable(builder.resourcesTasks);
        this.standaloneExtensions = CollectionUtils.unmodifiable(builder.standaloneExtensions);
        this.domainExtensions = CollectionUtils.unmodifiable(builder.domainExtensions);
        this.hostExtensions = CollectionUtils.unmodifiable(builder.hostExtensions);
        this.systemPaths = CollectionUtils.unmodifiable(builder.systemPaths);
        this.configStabilityLevel = builder.configStabilityLevel;
        this.packageStabilityLevel = builder.packageStabilityLevel;
        this.minimumStabilityLevel = builder.minimumStabilityLevel;
        this.stabilityLevel = builder.stabilityLevel;
    }

    public FeaturePackLocation getProducer() {
        return producer;
    }

    public Collection<String> getDefaultPackages() {
        return defaultPackages;
    }

    public Map<Gav, FeaturePackDependencySpec> getDependencies() {
        return dependencies;
    }

    public boolean hasSchemaGroups() {
        return !schemaGroups.isEmpty();
    }

    public boolean isSchemaGroup(String groupId) {
        return schemaGroups.contains(groupId);
    }

    public Set<String> getSchemaGroups() {
        return schemaGroups;
    }

    public boolean hasConfigs() {
        return !configs.isEmpty();
    }

    public List<ConfigModel> getConfigs() {
        return configs;
    }

    public boolean isIncludePlugin() {
        return includePlugin;
    }

    public boolean hasPlugins() {
        return !plugins.isEmpty();
    }

    public Map<String, ArtifactCoords> getPlugins() {
        return plugins;
    }

    public boolean hasResourcesTasks() {
        return !resourcesTasks.isEmpty();
    }

    public List<ResourcesTask> getResourcesTasks() {
        return resourcesTasks;
    }

    public boolean hasStandaloneExtensions() {
        return !standaloneExtensions.isEmpty();
    }

    public List<String> getStandaloneExtensions() {
        return standaloneExtensions;
    }

    public boolean hasDomainExtensions() {
        return !domainExtensions.isEmpty();
    }

    public List<String> getDomainExtensions() {
        return domainExtensions;
    }

    public boolean hasHostExtensions() {
        return !hostExtensions.isEmpty();
    }

    public List<String> getHostExtensions() {
        return hostExtensions;
    }

    public boolean hasSystemPaths() {
        return !systemPaths.isEmpty();
    }

    public Set<String> getSystemPaths() {
        return systemPaths;
    }

    public String getConfigStabilityLevel() {
        return configStabilityLevel;
    }

    public String getPackageStabilityLevel() {
        return packageStabilityLevel;
    }

    public String getMinimumStabilityLevel() {
        return minimumStabilityLevel;
    }

    public String getStabilityLevel() {
        return stabilityLevel;
    }
}
