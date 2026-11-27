/*
 * Copyright 2026-2026 Red Hat, Inc. and/or its affiliates
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
