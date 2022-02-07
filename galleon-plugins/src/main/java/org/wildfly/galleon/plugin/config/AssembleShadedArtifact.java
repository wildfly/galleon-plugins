/*
 * Copyright 2016-2024 Red Hat, Inc. and/or its affiliates
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



import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 * Represents an artifact that is assembled and copied in the final build.
 *
 *
 * @author Stuart Douglas
 * @author Alexey Loubyansky
 */
public class AssembleShadedArtifact implements WildFlyPackageTask {

    private String shadedModelPackage;
    private String toLocation;

    public AssembleShadedArtifact() {
    }

    public void setShadedModelPackage(String shadedModelPackage) {
        this.shadedModelPackage = shadedModelPackage;
    }

    public void setToLocation(String toLocation) {
        this.toLocation = toLocation;
    }

    public String getShadedModelPackage() {
        return shadedModelPackage;
    }

    public String getToLocation() {
        return toLocation;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        try {
            plugin.assembleArtifact(this, pkg);
        } catch (ProvisioningException e) {
            throw new ProvisioningException("Failed to execute an artifact assembling task of feature-pack " + pkg.getFeaturePackRuntime().getFPID() +
                    " package " + pkg.getName(), e);
        }
    }
}
