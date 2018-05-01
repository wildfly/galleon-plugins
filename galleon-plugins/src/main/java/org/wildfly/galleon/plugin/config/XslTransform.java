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

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 *
 * @author Alexey Loubyansky
 */
public class XslTransform implements WildFlyPackageTask {

    private String src;
    private String stylesheet;
    private String output;
    private Map<String, String> params = Collections.emptyMap();
    private Phase phase;

    public XslTransform() {
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void setStylesheet(String stylesheet) {
        this.stylesheet = stylesheet;
    }

    public void setParam(String name, String value) {
        params = CollectionUtils.put(params, name, value);
    }

    public void setPhase(String phase) {
        this.phase = Phase.valueOf(phase);
    }

    public String getSrc() {
        return src;
    }

    public String getStylesheet() {
        return stylesheet;
    }

    public String getOutput() {
        return output;
    }

    public boolean hasParams() {
        return !params.isEmpty();
    }

    public Map<String, String> getParams() {
        return params;
    }

    @Override
    public Phase getPhase() {
        return phase;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        plugin.xslTransform(pkg.getFeaturePackRuntime(), this, pkg.getResource(WfConstants.PM, WfConstants.WILDFLY));
    }
}
