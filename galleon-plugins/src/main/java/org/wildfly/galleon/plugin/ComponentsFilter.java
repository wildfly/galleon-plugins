/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import org.jboss.galleon.universe.maven.MavenArtifact;

interface ComponentsFilter {

    /**
     * Where a component is reported in the SBOM.
     *
     * @param included {@code true} if the component is part of the SBOM
     * @param framework the framework that provides the component, {@code null} if the component
     * belongs to the main product (or is not included).
     */
    record Placement(boolean included, FrameworkInfo framework) {
        static final Placement PRODUCT = new Placement(true, null);
        static final Placement EXCLUDED = new Placement(false, null);

        static Placement framework(FrameworkInfo framework) {
            return new Placement(true, framework);
        }
    }

    Placement placeMavenArtifact(MavenArtifact artifact, SbomArtifactRecorder.ProductRelease release);
    Placement placeShaded(String shadedName, SbomArtifactRecorder.ProductRelease release);
};
