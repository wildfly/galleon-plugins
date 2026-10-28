/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven.shaded;

import dev.cyberstamp.maven.assembly.sbom.ArtifactCoords;
import java.util.Collections;
import java.util.List;

/**
 * An artifact that shades dependencies.
 */
public final class FatJar {

    private final ArtifactCoords coords;
    private final List<ArtifactCoords> shadedDependencies;

    public FatJar(ArtifactCoords coords, List<ArtifactCoords> shadedDependencies) {
        this.coords = coords;
        this.shadedDependencies = Collections.unmodifiableList(shadedDependencies);
    }

    public ArtifactCoords getCoords() {
        return coords;
    }

    public List<ArtifactCoords> getShadedDependencies() {
        return shadedDependencies;
    }
}
