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

import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link WfInstallPlugin#resolveEffectiveVersion(MavenArtifact, boolean,
 * WfInstallPlugin.ArtifactResolver)}, which supplies channel-accurate versions to
 * the SBOM-only collection paths (module.xml artifacts, CopyArtifact tasks and
 * provisioning-tool artifacts).
 */
public class EffectiveVersionTestCase {

    @Test
    public void resolvesFromChannelWhenChannelResolutionEnabled() throws Exception {
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId("org.foo");
        artifact.setArtifactId("bar");
        // No declared version: the channel is the source of truth.

        final MavenArtifact result = WfInstallPlugin.resolveEffectiveVersion(artifact, true,
                a -> a.setVersion("9.9.9"));

        assertSame(artifact, result);
        assertEquals("9.9.9", result.getVersion());
    }

    @Test
    public void channelOverridesDeclaredVersion() throws Exception {
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId("org.foo");
        artifact.setArtifactId("bar");
        artifact.setVersion("1.0.0");

        final MavenArtifact result = WfInstallPlugin.resolveEffectiveVersion(artifact, true,
                a -> a.setVersion("2.0.0"));

        assertEquals("2.0.0", result.getVersion());
    }

    @Test
    public void doesNotResolveWhenChannelResolutionDisabled() throws Exception {
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId("org.foo");
        artifact.setArtifactId("bar");
        artifact.setVersion("1.2.3");

        final MavenArtifact result = WfInstallPlugin.resolveEffectiveVersion(artifact, false,
                a -> {
                    throw new AssertionError("resolver must not be invoked outside channel mode");
                });

        assertSame(artifact, result);
        assertEquals("1.2.3", result.getVersion());
    }

    @Test
    public void toleratesNullArtifact() throws Exception {
        final boolean[] invoked = {false};

        assertNull(WfInstallPlugin.resolveEffectiveVersion(null, true, a -> invoked[0] = true));
        assertNull(WfInstallPlugin.resolveEffectiveVersion(null, false, a -> invoked[0] = true));

        assertTrue("resolver must not be invoked for a null artifact", !invoked[0]);
    }
}
