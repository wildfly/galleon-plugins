/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.junit.Assert;
import org.junit.Test;
import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 *
 * @author jdenise
 */
public class FeaturePackBuildModelParserTestCase {

    @Test
    public void testParseAll() throws Exception {
        FeaturePackBuildModelParser parser = new FeaturePackBuildModelParser();
        WildFlyFeaturePackBuild build;
        try (InputStream stream = Files.newInputStream(Paths.get("src/test/resources/xml/test-feature-pack-build.xml"))) {
            build = parser.parse(stream);
        }
        Assert.assertEquals("level-1", build.getStabilityLevel());
        Assert.assertEquals("level-2", build.getMinimumStabilityLevel());
        Assert.assertEquals("level-3", build.getConfigStabilityLevel());
        Assert.assertEquals("level-4", build.getPackageStabilityLevel());

        Assert.assertEquals(3, build.getSystemPaths().size());
        Assert.assertTrue(build.getSystemPaths().contains("system-path-1"));
        Assert.assertTrue(build.getSystemPaths().contains("system-path-2"));
        Assert.assertTrue(build.getSystemPaths().contains("system-path-3"));

        Assert.assertEquals(3, build.getStandaloneExtensions().size());
        Assert.assertTrue(build.getStandaloneExtensions().contains("standalone-extension-1"));
        Assert.assertTrue(build.getStandaloneExtensions().contains("standalone-extension-1"));
        Assert.assertTrue(build.getStandaloneExtensions().contains("standalone-extension-1"));

        Assert.assertEquals(3, build.getDomainExtensions().size());
        Assert.assertTrue(build.getDomainExtensions().contains("domain-extension-1"));
        Assert.assertTrue(build.getDomainExtensions().contains("domain-extension-1"));
        Assert.assertTrue(build.getDomainExtensions().contains("domain-extension-1"));

        Assert.assertEquals(3, build.getHostExtensions().size());
        Assert.assertTrue(build.getHostExtensions().contains("host-extension-1"));
        Assert.assertTrue(build.getHostExtensions().contains("host-extension-1"));
        Assert.assertTrue(build.getHostExtensions().contains("host-extension-1"));

        Assert.assertEquals(3, build.getResourcesTasks().size());

        Assert.assertEquals(4, build.getPlugins().size());
        Assert.assertTrue(build.getPlugins().get("artifactId1").equals(ArtifactCoordsUtil.fromJBossModules("grpoupId1:artifactId1", "jar")));
        Assert.assertTrue(build.getPlugins().get("plugin1").equals(ArtifactCoordsUtil.fromJBossModules("grpoupId2:artifactId2:2.0", "jar")));
        Assert.assertTrue(build.getPlugins().get("plugin2").equals(ArtifactCoordsUtil.fromJBossModules("grpoupId3:artifactId3:3.0:classifier3", "jar")));
        Assert.assertTrue(build.getPlugins().get("plugin3").equals(ArtifactCoordsUtil.fromJBossModules("grpoupId3:artifactId3:3.0:classifier3:zip", "jar")));

        Assert.assertEquals(6, build.getDependencies().size());

        FeaturePackDependencySpec spec1 = build.getDependencies().get(ArtifactCoords.newGav("groupId-1:artifactId-1"));
        Assert.assertEquals("dependency-1", spec1.getName());
        Assert.assertEquals(Boolean.TRUE, spec1.getTarget().getInheritConfigs());
        Assert.assertEquals(true, spec1.getTarget().isInheritModelOnlyConfigs());
        Assert.assertTrue(spec1.getTarget().getIncludedConfigs().contains(new ConfigId("model-1", "default-config-1")));
        Assert.assertEquals(1, spec1.getTarget().getDefinedConfigs().size());
        Assert.assertTrue(spec1.getTarget().getDefinedConfigs().toArray(new ConfigModel[1])[0].isInheritFeatures());

        Assert.assertEquals(3, spec1.getTarget().getPatches().size());
        Assert.assertTrue(spec1.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch1:1").getFPID()));
        Assert.assertTrue(spec1.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch2:2").getFPID()));
        Assert.assertTrue(spec1.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch3:3").getFPID()));

        Assert.assertEquals(3, spec1.getTarget().getIncludedPackages().size());
        Assert.assertTrue(spec1.getTarget().getIncludedPackages().contains("package-1"));
        Assert.assertTrue(spec1.getTarget().getIncludedPackages().contains("package-2"));
        Assert.assertTrue(spec1.getTarget().getIncludedPackages().contains("package-3"));

        Assert.assertEquals(3, spec1.getTarget().getExcludedPackages().size());
        Assert.assertTrue(spec1.getTarget().getExcludedPackages().contains("package-4"));
        Assert.assertTrue(spec1.getTarget().getExcludedPackages().contains("package-5"));
        Assert.assertTrue(spec1.getTarget().getExcludedPackages().contains("package-6"));

        FeaturePackDependencySpec spec2 = build.getDependencies().get(ArtifactCoords.newGav("groupId-2:artifactId-2"));
        Assert.assertNull(spec2.getName());

        FeaturePackDependencySpec spec3 = build.getDependencies().get(ArtifactCoords.newGav("groupId-3:artifactId-3"));
        Assert.assertNull(spec3.getName());

        FeaturePackDependencySpec spec4 = build.getDependencies().get(ArtifactCoords.newGav("tgroupId-1:tartifactId-1"));
        Assert.assertEquals("transitive-1", spec4.getName());
        Assert.assertEquals(Boolean.TRUE, spec4.getTarget().getInheritConfigs());
        Assert.assertEquals(true, spec4.getTarget().isInheritModelOnlyConfigs());
        Assert.assertTrue(spec4.getTarget().getIncludedConfigs().contains(new ConfigId("model-1", "default-config-1")));

        Assert.assertEquals(3, spec4.getTarget().getPatches().size());
        Assert.assertTrue(spec4.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch1:1").getFPID()));
        Assert.assertTrue(spec4.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch2:2").getFPID()));
        Assert.assertTrue(spec4.getTarget().getPatches().contains(FeaturePackLocation.fromString("org:patch3:3").getFPID()));

        Assert.assertEquals(3, spec4.getTarget().getIncludedPackages().size());
        Assert.assertTrue(spec4.getTarget().getIncludedPackages().contains("package-1"));
        Assert.assertTrue(spec4.getTarget().getIncludedPackages().contains("package-2"));
        Assert.assertTrue(spec4.getTarget().getIncludedPackages().contains("package-3"));

        Assert.assertEquals(3, spec4.getTarget().getExcludedPackages().size());
        Assert.assertTrue(spec4.getTarget().getExcludedPackages().contains("package-4"));
        Assert.assertTrue(spec4.getTarget().getExcludedPackages().contains("package-5"));
        Assert.assertTrue(spec4.getTarget().getExcludedPackages().contains("package-6"));

        FeaturePackDependencySpec spec5 = build.getDependencies().get(ArtifactCoords.newGav("tgroupId-2:tartifactId-2"));
        Assert.assertNull(spec5.getName());

        FeaturePackDependencySpec spec6 = build.getDependencies().get(ArtifactCoords.newGav("tgroupId-3:tartifactId-3"));
        Assert.assertNull(spec6.getName());
    }

}
