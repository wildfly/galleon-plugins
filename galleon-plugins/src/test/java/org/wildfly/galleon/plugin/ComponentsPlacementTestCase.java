/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.wildfly.galleon.plugin.ComponentsFilter.Placement;
import org.wildfly.galleon.plugin.SbomArtifactRecorder.ProductRelease;
import org.wildfly.galleon.plugin.WfInstallPlugin.FeaturePackSbom;

/**
 * Tests the decision made by {@link WfInstallPlugin#place} to include, exclude, or report as a framework
 * a component, according to the CPE of the release and the SBOM information of its feature-pack.
 */
public class ComponentsPlacementTestCase {

    private static final String CPE = "cpe:/a:redhat:eap:8.1";
    private static final String PRODUCER = "org.foo:my-fp";
    private static final ProductRelease RELEASE_WITH_CPE = new ProductRelease("EAP", "8.1.0", "Red Hat", CPE);
    private static final ProductRelease RELEASE_WITHOUT_CPE = new ProductRelease("EAP", "8.1.0", "Red Hat", null);

    private static Map<String, FeaturePackSbom> sboms(FeaturePackSbom sbom) {
        final Map<String, FeaturePackSbom> map = new HashMap<>();
        map.put(PRODUCER, sbom);
        return map;
    }

    private static FrameworkInfo framework() {
        return FrameworkInfo.of(CpeResolutionMode.LOCAL, "cpe:/a:redhat:xp:7.0", "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final");
    }

    @Test
    public void localModeWithoutIdentityIsExcluded() {
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, PRODUCER,
                sboms(new FeaturePackSbom(CpeResolutionMode.LOCAL, null)));
        assertFalse(placement.included());
        assertSame(Placement.EXCLUDED, placement);
    }

    @Test
    public void noModeWithoutIdentityIsExcluded() {
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, PRODUCER,
                sboms(new FeaturePackSbom(null, null)));
        assertFalse(placement.included());
        assertSame(Placement.EXCLUDED, placement);
    }

    @Test
    public void unknownProducerIsExcluded() {
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, "org.foo:other-fp",
                sboms(new FeaturePackSbom(CpeResolutionMode.SERVER_PRODUCT_CONF, null)));
        assertFalse(placement.included());
        assertSame(Placement.EXCLUDED, placement);
    }

    @Test
    public void noProducerIsExcluded() {
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, null, new HashMap<>());
        assertFalse(placement.included());
        assertSame(Placement.EXCLUDED, placement);
    }

    @Test
    public void nonLocalModeIsProduct() {
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, PRODUCER,
                sboms(new FeaturePackSbom(CpeResolutionMode.SERVER_PRODUCT_CONF, null)));
        assertSame(Placement.PRODUCT, placement);
    }

    @Test
    public void localModeWithIdentityIsFramework() {
        final FrameworkInfo framework = framework();
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, PRODUCER,
                sboms(new FeaturePackSbom(CpeResolutionMode.LOCAL, framework)));
        assertTrue(placement.included());
        assertSame(framework, placement.framework());
    }

    @Test
    public void noModeWithIdentityIsFramework() {
        final FrameworkInfo framework = framework();
        final Placement placement = WfInstallPlugin.place(RELEASE_WITH_CPE, PRODUCER,
                sboms(new FeaturePackSbom(null, framework)));
        assertTrue(placement.included());
        assertSame(framework, placement.framework());
    }

    @Test
    public void releaseWithoutCpeIsProduct() {
        // Whatever the feature-pack information is.
        assertSame(Placement.PRODUCT, WfInstallPlugin.place(RELEASE_WITHOUT_CPE, PRODUCER,
                sboms(new FeaturePackSbom(CpeResolutionMode.LOCAL, null))));
        assertSame(Placement.PRODUCT, WfInstallPlugin.place(RELEASE_WITHOUT_CPE, null, new HashMap<>()));
        assertSame(Placement.PRODUCT, WfInstallPlugin.place(RELEASE_WITHOUT_CPE, "org.foo:other-fp",
                sboms(new FeaturePackSbom(CpeResolutionMode.LOCAL, null))));
    }

    @Test
    public void nullReleaseIsProduct() {
        final Placement placement = WfInstallPlugin.place(null, PRODUCER,
                sboms(new FeaturePackSbom(CpeResolutionMode.LOCAL, null)));
        assertSame(Placement.PRODUCT, placement);
        assertNull(placement.framework());
        assertEquals(true, placement.included());
    }
}
