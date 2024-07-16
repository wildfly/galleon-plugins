/*
 * Copyright 2016-2024 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugin.logging.Log;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Stability;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.spec.ConfigLayerSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.spec.PackageSpec;
import org.junit.Test;

public class StabilityLevelTestCase {

    @Test
    public void testPackageAtLowerStabilityLevel() throws Exception {
        Set<String> lowerStabilityPackages = new HashSet<>();
        lowerStabilityPackages.add("exp1");
        lowerStabilityPackages.add("exp2");
        lowerStabilityPackages.add("exp3");

        Set<PackageSpec> packages = new HashSet<>();
        packages.add(PackageSpec.builder("pkg1").addPackageDep("exp1").build());

        Set<ConfigLayerSpec> layers = new HashSet<>();
        layers.add(ConfigLayerSpec.builder().setName("layer1").addPackageDep("exp2").build());

        Set<FeatureSpec> features = new HashSet<>();
        features.add(FeatureSpec.builder().setName("feat1").addPackageDep("exp3").build());

        Map<String, Map<String, ConfigModel>> configs = new HashMap<>();
        Map<String, ConfigModel> map = new HashMap<>();
        configs.put("standalone", map);
        map.put(Constants.MODEL_XML, ConfigModel.builder("standalone", Constants.MODEL_XML).addPackageDep("exp1").build());
        Log log = new Log() {
            @Override
            public boolean isDebugEnabled() {
                return false;
            }

            @Override
            public void debug(CharSequence cs) {
                System.out.println(cs);
            }

            @Override
            public void debug(CharSequence cs, Throwable thrwbl) {
                System.out.println(cs);
            }

            @Override
            public void debug(Throwable thrwbl) {
                System.out.println(thrwbl.toString());
            }

            @Override
            public boolean isInfoEnabled() {
                return true;
            }

            @Override
            public void info(CharSequence cs) {
                System.out.println(cs);
            }

            @Override
            public void info(CharSequence cs, Throwable thrwbl) {
                System.out.println(cs);
            }

            @Override
            public void info(Throwable thrwbl) {
                System.out.println(thrwbl.toString());
            }

            @Override
            public boolean isWarnEnabled() {
                return true;
            }

            @Override
            public void warn(CharSequence cs) {
                System.out.println(cs);
            }

            @Override
            public void warn(CharSequence cs, Throwable thrwbl) {
                System.out.println(cs);
            }

            @Override
            public void warn(Throwable thrwbl) {
                System.out.println(thrwbl.toString());
            }

            @Override
            public boolean isErrorEnabled() {
                return false;
            }

            @Override
            public void error(CharSequence cs) {
                System.out.println(cs);
            }

            @Override
            public void error(CharSequence cs, Throwable thrwbl) {
                System.out.println(cs);
            }

            @Override
            public void error(Throwable thrwbl) {
                System.out.println(thrwbl.toString());
            }
        };
        // No check, should pass
        AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                false,
                lowerStabilityPackages,
                packages,
                layers,
                features,
                configs,
                log);

        // No package at lower stability level, should pass
        AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                false,
                Collections.emptySet(),
                packages,
                layers,
                features,
                configs,
                log);

        {
            boolean failed = false;
            try {
                AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                        true,
                        lowerStabilityPackages,
                        packages,
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptyMap(),
                        log);
                failed = true;
            } catch (Exception ex) {
                // XXX expected
            }
            if (failed) {
                throw new Exception("The test case should have failed");
            }
        }
        {
            boolean failed = false;
            try {
                AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                        true,
                        lowerStabilityPackages,
                        Collections.emptySet(),
                        layers,
                        Collections.emptySet(),
                        Collections.emptyMap(),
                        log);
                failed = true;
            } catch (Exception ex) {
                // XXX expected
            }
            if (failed) {
                throw new Exception("The test case should have failed");
            }
        }
        {
            boolean failed = false;
            try {
                AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                        true,
                        lowerStabilityPackages,
                        Collections.emptySet(),
                        Collections.emptySet(),
                        features,
                        Collections.emptyMap(),
                        log);
                failed = true;
            } catch (Exception ex) {
                // XXX expected
            }
            if (failed) {
                throw new Exception("The test case should have failed");
            }
        }
        {
            boolean failed = false;
            try {
                AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                        true,
                        lowerStabilityPackages,
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        configs,
                        log);
                failed = true;
            } catch (Exception ex) {
                // XXX expected
            }
            if (failed) {
                throw new Exception("The test case should have failed");
            }
        }
        {
            boolean failed = false;
            try {
                AbstractFeaturePackBuildMojo.checkFeaturePackContentStability(Stability.EXPERIMENTAL,
                        true,
                        lowerStabilityPackages,
                        packages,
                        layers,
                        features,
                        configs,
                        log);
                failed = true;
            } catch (Exception ex) {
                // XXX expected
            }
            if (failed) {
                throw new Exception("The test case should have failed");
            }
        }
    }
}
