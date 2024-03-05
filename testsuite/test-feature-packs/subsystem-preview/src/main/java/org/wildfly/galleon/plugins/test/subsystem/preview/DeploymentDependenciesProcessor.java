/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugins.test.subsystem.preview;

import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.Phase;

final class DeploymentDependenciesProcessor implements DeploymentUnitProcessor {

    /**
     * See {@link Phase} for a description of the different phases
     */
    static final Phase PHASE = Phase.DEPENDENCIES;

    /**
     * The relative order of this processor within the {@link #PHASE}.
     * The current number is large enough for it to happen after all
     * the standard deployment unit processors that come with WildFly.
     */
    static final int PRIORITY = Phase.DEPENDENCIES_JAXRS + 1;

    @Override
    public void deploy(DeploymentPhaseContext phaseContext) {
    }

}
