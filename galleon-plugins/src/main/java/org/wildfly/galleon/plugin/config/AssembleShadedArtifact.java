/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
