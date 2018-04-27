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

package org.wildfly.galleon.plugin.config;

import java.util.Collections;
import java.util.Map;

import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.util.CollectionUtils;

/**
 * This is hopefully a temporary class which represents configs from a feature-pack
 * that should be provisioned and copied to the docs/examples/configs.
 *
 * @author Alexey Loubyansky
 */
public class ExampleFpConfigs {

    public static class Builder {

        private final String origin;
        private Map<ConfigId, String> configs = Collections.emptyMap();

        private Builder(String origin) {
            this.origin = origin;
        }

        public Builder addConfig(ConfigId configId, String group) {
            configs = CollectionUtils.put(configs, configId, group);
            return this;
        }

        public ExampleFpConfigs build() {
            return new ExampleFpConfigs(this);
        }
    }

    public static Builder builder() {
        return builder(null);
    }

    public static Builder builder(String origin) {
        return new Builder(origin);
    }

    private final String origin;
    private Map<ConfigId, String> configs;

    private ExampleFpConfigs(Builder builder) {
        this.origin = builder.origin;
        this.configs = builder.configs;
    }

    public String getOrigin() {
        return origin;
    }

    public Map<ConfigId, String> getConfigs() {
        return configs;
    }

    public void addAll(ExampleFpConfigs exampleConfigs) {
        for(Map.Entry<ConfigId, String> config : exampleConfigs.configs.entrySet()) {
            configs = CollectionUtils.put(configs, config.getKey(), config.getValue());
        }
    }
}
