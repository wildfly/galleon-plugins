/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link WfInstallPlugin#booleanOptionValue(boolean, String, String)},
 * which underpins default-on options such as {@code jboss-cyclonedx}.
 */
public class BooleanOptionValueTestCase {

    @Test
    public void unsetWithoutDefaultIsOff() {
        assertFalse(WfInstallPlugin.booleanOptionValue(false, null, null));
    }

    @Test
    public void unsetWithTrueDefaultIsOn() {
        assertTrue(WfInstallPlugin.booleanOptionValue(false, null, "true"));
    }

    @Test
    public void unsetWithFalseDefaultIsOff() {
        assertFalse(WfInstallPlugin.booleanOptionValue(false, null, "false"));
    }

    @Test
    public void explicitFalseOverridesTrueDefault() {
        assertFalse(WfInstallPlugin.booleanOptionValue(true, "false", "true"));
    }

    @Test
    public void explicitTrueIsOn() {
        assertTrue(WfInstallPlugin.booleanOptionValue(true, "true", "false"));
    }

    @Test
    public void bareFlagIsOn() {
        assertTrue(WfInstallPlugin.booleanOptionValue(true, null, null));
    }
}
