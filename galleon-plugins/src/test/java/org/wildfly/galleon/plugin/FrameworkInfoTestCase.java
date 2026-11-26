/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FrameworkInfoTestCase {

    private static final String CPE = "cpe:/a:redhat:xp:7.0";

    @Test
    public void localWithCompleteIdentityIsFramework() {
        final FrameworkInfo info = FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final");
        assertNotNull(info);
        assertEquals(CPE, info.getCpe());
        assertEquals("XP", info.getName());
        assertEquals("Red Hat", info.getPublisher());
        assertEquals("7.0.0.final", info.getVersion());
        assertEquals("pkg:generic/xp@7.0.0.final", info.getPurl());
    }

    @Test
    public void nullVersionUsesPurlVersion() {
        final FrameworkInfo info = FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp@1.0?type=jar#sub", "Red Hat", null);
        assertNotNull(info);
        assertEquals("1.0", info.getVersion());
        assertEquals("pkg:generic/xp@1.0?type=jar#sub", info.getPurl());
    }

    @Test
    public void nullVersionAndPurlWithoutVersionIsUnknown() {
        final FrameworkInfo info = FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp", "Red Hat", null);
        assertNotNull(info);
        assertEquals(FrameworkInfo.UNKNOWN_VERSION, info.getVersion());
        assertEquals("pkg:generic/xp@unknown", info.getPurl());
    }

    @Test
    public void purlVersionTakesPrecedence() {
        final FrameworkInfo info = FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:generic/xp@1.0", "Red Hat", "7.0.0.Final");
        assertEquals("pkg:generic/xp@1.0", info.getPurl());
        assertEquals("1.0", info.getVersion());
    }

    @Test
    public void serverProductConfModeHasNoIdentity() {
        assertNull(FrameworkInfo.of(CpeResolutionMode.SERVER_PRODUCT_CONF, CPE, "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final"));
    }

    @Test
    public void nullModeIsLocal() {
        final FrameworkInfo info = FrameworkInfo.of(null, CPE, "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final");
        assertNotNull(info);
        assertEquals("pkg:generic/xp@7.0.0.final", info.getPurl());
    }

    @Test
    public void noCpeHasNoIdentity() {
        assertNull(FrameworkInfo.of(CpeResolutionMode.LOCAL, null, "XP", "pkg:generic/xp", "Red Hat", "7.0.0.Final"));
        assertNull(FrameworkInfo.of(null, null, null, null, null, "7.0.0.Final"));
    }

    @Test
    public void noNameOrPurlHasNoIdentity() {
        assertNull(FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, null, "pkg:generic/xp", "Red Hat", "7.0.0.Final"));
        assertNull(FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", null, "Red Hat", "7.0.0.Final"));
    }

    @Test
    public void publisherIsOptional() {
        final FrameworkInfo info = FrameworkInfo.of(CpeResolutionMode.LOCAL, CPE, "XP", "pkg:maven/org.foo/xp@1.0", null, "1.0");
        assertNotNull(info);
        assertNull(info.getPublisher());
        assertEquals("pkg:maven/org.foo/xp@1.0", info.getPurl());
    }
}
