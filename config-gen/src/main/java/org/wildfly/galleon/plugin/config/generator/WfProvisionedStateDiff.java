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

package org.wildfly.galleon.plugin.config.generator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.plugin.ProvisionedConfigHandler;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.state.ProvisionedConfig;
import org.jboss.galleon.state.ProvisionedFeature;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.jboss.galleon.util.CollectionUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfProvisionedStateDiff {

    public static class FeatureSpecMapper implements ProvisionedConfigHandler {

        private Map<String, List<ProvisionedFeature>> features;
        private String specName;
        private List<ProvisionedFeature> specFeatures;

        public Map<String, List<ProvisionedFeature>> arrangeBySpecName(ProvisionedConfig config) throws ProvisioningException {
            features = new HashMap<>(config.size());
            config.handle(this);
            final Map<String, List<ProvisionedFeature>> tmp = features;
            features = null;
            specName = null;
            specFeatures = null;
            return tmp;
        }

        @Override
        public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
            specName = spec.getName();
            specFeatures = features.get(specName);
            if(specFeatures == null) {
                specFeatures = new ArrayList<>();
                features.put(specName, specFeatures);
            }
        }

        @Override
        public void nextFeature(ProvisionedFeature feature) throws ProvisioningException {
            specFeatures.add(feature);
        }
    }

    public static List<ConfigModel> exportDiff (ProvisioningRuntime runtime, Map<FPID, ConfigId> includedConfigs, Path customizedInstallation, Path target) throws ProvisioningException {

        return new WfProvisionedStateDiff().diff(runtime);
    }

    private final FeatureSpecMapper fsMapper = new FeatureSpecMapper();

    private WfProvisionedStateDiff() {
    }

    private List<ConfigModel> diff(ProvisioningRuntime runtime) throws ProvisioningException {
        List<ConfigModel> configs = Collections.emptyList();
        for(ProvisionedConfig config : runtime.getConfigs()) {
            final ConfigModel configDiff = diffConfig(config);
            if(configDiff != null) {
                configs = CollectionUtils.add(configs, configDiff);
            }
        }
        return configs;
    }

    private ConfigModel diffConfig(ProvisionedConfig config) throws ProvisioningException {

        final Map<String, List<ProvisionedFeature>> arrangedBySpec = fsMapper.arrangeBySpecName(config);
        return ConfigModel.builder().build();
    }
}
