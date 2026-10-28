/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;

/**
 *
 * @author Alexey Loubyansky
 */
public interface WildFlyPackageTask {

    enum Phase {
        PROCESSING,
        FINALIZING;
    }

    default Phase getPhase() {
        return Phase.PROCESSING;
    }

    void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException;
}
