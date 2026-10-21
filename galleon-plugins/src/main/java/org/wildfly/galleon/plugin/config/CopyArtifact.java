/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;


import java.util.Collections;
import java.util.List;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 * Represents an artifact that is copies into a specific location in the final
 * build.
 *
 *
 * @author Stuart Douglas
 * @author Alexey Loubyansky
 */
public class CopyArtifact implements WildFlyPackageTask {

    private String artifact;
    private String toLocation;
    private boolean extract;
    private List<FileFilter> filters = Collections.emptyList();
    private boolean optional;
    private boolean featurePackVersion;

    public CopyArtifact() {
    }

    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    public void setToLocation(String toLocation) {
        this.toLocation = toLocation;
    }

    public void setExtract() {
        this.extract = true;
    }

    public void addFilter(FileFilter filter) {
        filters = CollectionUtils.add(filters, filter);
    }

    public String getArtifact() {
        return artifact;
    }

    public String getToLocation() {
        return toLocation;
    }

    public boolean isExtract() {
        return extract;
    }

    public List<FileFilter> getFilters() {
        return filters;
    }

    public void setOptional() {
        this.optional = true;
    }

    public boolean isOptional() {
        return optional;
    }

    public boolean includeFile(final String path) {
        for(FileFilter filter : filters) {
            if(filter.matches(path)) {
                return filter.isInclude();
            }
        }
        return true; //default include
    }

    public void setFeaturePackVersion() {
        featurePackVersion = true;
    }

    public boolean isFeaturePackVersion() {
        return featurePackVersion;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        try {
            plugin.copyArtifact(this, pkg);
        } catch (ProvisioningException e) {
            throw new ProvisioningException("Failed to execute an artifact copying task of feature-pack " + pkg.getFeaturePackRuntime().getFPID() +
                    " package " + pkg.getName(), e);
        }
    }
}
