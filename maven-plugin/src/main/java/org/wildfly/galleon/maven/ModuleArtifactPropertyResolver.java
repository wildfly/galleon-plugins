/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;

import org.wildfly.galleon.plugin.PropertyResolver;

/**
 *
 * @author Alexey Loubyansky
 */
class ModuleArtifactPropertyResolver implements PropertyResolver {

    private final MavenProjectArtifactVersions versions;

    ModuleArtifactPropertyResolver(MavenProjectArtifactVersions versions) {
        this.versions = versions;
    }

    /* (non-Javadoc)
     * @see org.jboss.galleon.maven.plugin.wildfly.PropertyResolver#resolveProperty(java.lang.String)
     */
    @Override
    public String resolveProperty(String property) {
        return versions.getVersion(property);
    }

}
