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
 *
 * @author Alexey Loubyansky
 */
public class DeletePath implements WildFlyPackageTask {

    private final String path;
    private final boolean recursive;
    private final boolean ifEmpty;

    DeletePath(String path, boolean recursive, boolean ifEmpty) {
        this.path = path;
        this.recursive = recursive;
        this.ifEmpty = ifEmpty;
    }

    @Override
    public Phase getPhase() {
        return Phase.FINALIZING;
    }

    public String getPath() {
        return path;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public boolean isIfEmpty() {
        return ifEmpty;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        plugin.deletePath(this);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (ifEmpty ? 1231 : 1237);
        result = prime * result + ((path == null) ? 0 : path.hashCode());
        result = prime * result + (recursive ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DeletePath other = (DeletePath) obj;
        if (ifEmpty != other.ifEmpty)
            return false;
        if (path == null) {
            if (other.path != null)
                return false;
        } else if (!path.equals(other.path))
            return false;
        if (recursive != other.recursive)
            return false;
        return true;
    }
}
