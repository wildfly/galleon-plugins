/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import org.jboss.galleon.universe.maven.MavenArtifact;

interface ComponentsFilter {

    boolean includeMavenArtifact(MavenArtifact artifact, SbomArtifactRecorder.ProductRelease release);
    boolean includeShaded(String shadedName, SbomArtifactRecorder.ProductRelease release);
};
