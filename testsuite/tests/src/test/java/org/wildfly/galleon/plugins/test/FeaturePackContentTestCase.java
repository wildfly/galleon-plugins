/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugins.test;

import java.io.BufferedReader;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.Stability;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.plugin.ProvisionedConfigHandler;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.state.ProvisionedConfig;
import org.jboss.galleon.state.ProvisionedFeaturePack;
import org.jboss.galleon.state.ProvisionedState;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.jboss.galleon.xml.FeatureSpecXmlParser;
import org.jboss.galleon.xml.PackageXmlParser;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Check stability. 1) Check that the content of the feature-pack is valid
 * according to its minimum stability. It must not contain features and packages
 * at a lower stability level. 2) Check that what has been provisioned is
 * correct according to the stability level set at provisioning time. If no
 * stability level is provided, then the minimum stability level of the
 * feature-pack has to be used.
 *
 * @author jdenise
 */
public class FeaturePackContentTestCase {

    public static String root;

    @BeforeClass
    public static void setUp() {
        root = System.getProperty("servers.install.root");
    }

    @Test
    public void checkFeaturePacksContent() throws Exception {
        File[] installations = new File(root).listFiles(File::isDirectory);
        for (File f : installations) {
            checkContent(f.toPath());

        }
    }

    private void checkContent(Path home) throws Exception {
        System.out.println("\nPROVISIONED HOME " + home);
        SimplisticMavenRepoManager repoManager = SimplisticMavenRepoManager.getInstance();
        boolean foundFP = false;
        int numPackages = 0;
        int numFeatures = 0;
        try (ProvisioningManager pm = ProvisioningManager.builder()
                .setInstallationHome(home)
                .addArtifactResolver(repoManager)
                .build()) {
            String stabilityLevel = pm.getProvisioningConfig().getOption("stability-level");
            Stability enforcedStability = stabilityLevel == null ? null : Stability.fromString(stabilityLevel);
            if (enforcedStability != null) {
                System.out.println("User set stability " + enforcedStability);
            }
            try (ProvisioningLayout<FeaturePackLayout> pl = pm.getLayoutFactory().newConfigLayout(pm.getProvisioningConfig())) {
                for (FeaturePackLayout fl : pl.getOrderedFeaturePacks()) {
                    if (!fl.getFPID().toString().startsWith("wildfly-core@")) {
                        Set<String> expectedPackages = new HashSet<>();
                        Set<String> expectedFeatures = new HashSet<>();
                        System.out.println("Scanning " + fl.getFPID());
                        foundFP = true;
                        Stability minStability = fl.getSpec().getMinStability();
                        System.out.println("Feature-pack minimum stability " + minStability);
                        Path dir = fl.getDir();
                        Path packages = dir.resolve(Constants.PACKAGES);
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packages)) {
                            for (Path packageDir : stream) {
                                final Path packageFile = packageDir.resolve(Constants.PACKAGE_XML);
                                final PackageSpec pkgSpec;
                                try (BufferedReader reader = Files.newBufferedReader(packageFile)) {
                                    pkgSpec = PackageXmlParser.getInstance().parse(reader);
                                }
                                Stability packageStability = pkgSpec.getStability();
                                if (packageStability != null) {
                                    if (minStability != null && !minStability.enables(packageStability)) {
                                        throw new Exception("Package " + pkgSpec.getName() + " shouldn't be contained by feature-pack " + fl.getFPID() + " stability " + minStability);
                                    }
                                }
                                if (!"modules.all".equals(pkgSpec.getName())) {
                                    if (packageStability == null || (enforcedStability != null && enforcedStability.enables(packageStability))) {
                                        expectedPackages.add(pkgSpec.getName());
                                    } else {
                                        System.out.println("Package " + pkgSpec.getName() + " shouldn't be provisioned, not enabled by user provided stability");
                                    }
                                }
                                numPackages += 1;
                            }
                        }
                        Path features = dir.resolve(Constants.FEATURES);
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(features)) {
                            for (Path featureDir : stream) {
                                final Path featureXml = featureDir.resolve(Constants.SPEC_XML);
                                final FeatureSpec featureSpec;
                                try (BufferedReader reader = Files.newBufferedReader(featureXml)) {
                                    featureSpec = FeatureSpecXmlParser.getInstance().parse(reader);
                                }
                                Stability featureStability = featureSpec.getStability();
                                if (featureStability != null) {
                                    if (minStability != null && !minStability.enables(featureStability)) {
                                        throw new Exception("Feature " + featureSpec.getName() + " shouldn't be contained in the feature-pack. "
                                                + "Feature stability '"
                                                + featureStability + "' is not enabled by the '" + minStability
                                                + "' stability level that is the feature-pack minimum stability level.");
                                    }
                                }
                                if (!featureSpec.getName().startsWith("foo.bar")) {
                                    if (enforcedStability != null && enforcedStability.enables(featureStability)) {
                                        expectedFeatures.add(featureSpec.getName());
                                    } else {
                                        System.out.println("Feature " + featureSpec.getName() + " shouldn't be provisioned, not enabled by user provided stability");
                                    }
                                }
                                numFeatures += 1;
                            }
                        }
                        // Check the provisioned state
                        ProvisionedState state = pm.getProvisionedState();
                        ProvisionedFeaturePack fp = state.getFeaturePack(fl.getSpec().getFPID().getProducer());
                        System.out.println("EXPECTED PACKAGES ");
                        for (String s : expectedPackages) {
                            System.out.println(s);
                        }
                        System.out.println("PROVISIONED PACKAGES ");
                        for (String s : fp.getPackageNames()) {
                            System.out.println(s);
                        }
                        Assert.assertTrue("ALL " + fp.getPackageNames() + " EXPECTED " + expectedPackages, fp.getPackageNames().containsAll(expectedPackages));
                        ProvisionedConfig config = state.getConfigs().get(0);
                        Set<String> provisionedFeatures = new HashSet<>();
                        config.handle(new ProvisionedConfigHandler() {
                            public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
                                if (spec.getId().getProducer().equals(fl.getSpec().getFPID().getProducer())) {
                                    provisionedFeatures.add(spec.getName());
                                }
                            }
                        });
                        System.out.println("EXPECTED FEATURES ");
                        for (String s : expectedFeatures) {
                            System.out.println(s);
                        }
                        System.out.println("PROVISIONED FEATURES ");
                        for (String s : provisionedFeatures) {
                            System.out.println(s);
                        }
                        Assert.assertTrue("ALL " + provisionedFeatures + " EXPECTED " + expectedFeatures, provisionedFeatures.containsAll(expectedFeatures));
                    }
                }

            }
        }
        if (!foundFP) {
            throw new Exception("No featurePack found in " + home);
        }
        if (numPackages == 0) {
            throw new Exception("No packages found in " + home);
        }
        if (numFeatures == 0) {
            throw new Exception("No features found in " + home);
        }

    }
}
