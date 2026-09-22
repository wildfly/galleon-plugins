/*
 * Copyright 2016-2026 Red Hat, Inc. and/or its affiliates
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
