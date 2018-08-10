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

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        plugin.copyArtifact(pkg.getName(), this);
    }
}
