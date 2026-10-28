/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;

import java.util.Collections;
import java.util.Map;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 * This is hopefully a temporary class which represents configs from a feature-pack
 * that should be provisioned and copied to the docs/examples/configs.
 *
 * @author Alexey Loubyansky
 */
public class ExampleFpConfigs implements WildFlyPackageTask {

    private String origin;
    private Map<ConfigId, ConfigModel> configs = Collections.emptyMap();

    public void addConfig(ConfigModel config) {
        configs = CollectionUtils.put(configs, config.getId(), config);
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getOrigin() {
        return origin;
    }

    public Map<ConfigId, ConfigModel> getConfigs() {
        return configs;
    }

    public void addAll(ExampleFpConfigs exampleConfigs) {
        for(Map.Entry<ConfigId, ConfigModel> config : exampleConfigs.configs.entrySet()) {
            configs = CollectionUtils.put(configs, config.getKey(), config.getValue());
        }
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        plugin.addExampleConfigs(pkg.getFeaturePackRuntime(), this);
    }
}
