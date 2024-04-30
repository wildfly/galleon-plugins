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

import java.util.HashSet;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.Stability;
import org.junit.Assert;
import org.junit.Test;

public class StabilityOptionsTestCase {

    private static final Set<Boolean> OPTIONS = new HashSet<>();
    private static final Set<Boolean> OVERRIDES = new HashSet<>();

    static {
        OPTIONS.add(Boolean.TRUE);
        OPTIONS.add(Boolean.FALSE);

        OVERRIDES.add(Boolean.TRUE);
        OVERRIDES.add(Boolean.FALSE);
    }

    enum StabilityOption {
        MINIMUM,
        CONFIG,
        PACKAGE,
        STABILITY
    };

    @Test
    public void testValidOptions() throws Exception {
        doTestNoStabilityOption(new WfFeaturePackBuildMojo());
        doTestNoStabilityOption(new UserFeaturePackBuildMojo());
        for (Boolean option : OPTIONS) {
            for (Boolean override : OVERRIDES) {
                doTestStabilityOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.MINIMUM);
                doTestStabilityOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.MINIMUM);

                doTestStabilityOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.CONFIG);
                doTestStabilityOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.CONFIG);

                doTestStabilityOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.PACKAGE);
                doTestStabilityOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.PACKAGE);

                doTestStabilityOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.STABILITY);
                doTestStabilityOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.STABILITY);
            }
        }

    }

    void doTestNoStabilityOption(AbstractFeaturePackBuildMojo mojo) throws Exception {
        mojo.setStability(WildFlyFeaturePackBuild.builder().build());
        Assert.assertNull(mojo.getConfigStabilityLevel());
        Assert.assertNull(mojo.getPackageStabilityLevel());
        Assert.assertNull(mojo.getMinimumStabilityLevel());
    }

    void doTestStabilityOption(AbstractFeaturePackBuildMojo mojo, boolean option, boolean override, StabilityOption kind) throws Exception {
        WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        Stability expected = Stability.DEFAULT;
        // Always set a valid minimum stability level
        if (option) {
            mojo.minimumStabilityLevel = Stability.EXPERIMENTAL.toString();
        }
        if (override || !option) {
            builder.setMinimumStabilityLevel(Stability.EXPERIMENTAL.toString());
        }
        switch (kind) {
            case MINIMUM:
                if (option) {
                    expected = Stability.COMMUNITY;
                    mojo.minimumStabilityLevel = expected.toString();
                }
                if (override || !option) {
                    builder.setMinimumStabilityLevel(expected.toString());
                }
                break;
            case STABILITY:
                if (option) {
                    expected = Stability.COMMUNITY;
                    mojo.stabilityLevel = expected.toString();
                }
                if (override || !option) {
                    builder.setStabilityLevel(expected.toString());
                }
                break;
            case PACKAGE:
                if (option) {
                    expected = Stability.COMMUNITY;
                    mojo.packageStabilityLevel = expected.toString();
                }
                if (override || !option) {
                    builder.setPackageStabilityLevel(expected.toString());
                }
                break;
            case CONFIG:
                // Needed, package must imply config.
                mojo.packageStabilityLevel = Stability.EXPERIMENTAL.toString();
                if (option) {
                    expected = Stability.COMMUNITY;
                    mojo.configStabilityLevel = expected.toString();
                }
                if (override || !option) {
                    builder.setConfigStabilityLevel(expected.toString());
                }
                break;
        }
        mojo.setStability(builder.build());
        switch (kind) {
            case MINIMUM:
                Assert.assertEquals(expected, mojo.getMinimumStabilityLevel());
                break;
            case STABILITY:
                Assert.assertEquals(expected, mojo.getPackageStabilityLevel());
                Assert.assertEquals(expected, mojo.getConfigStabilityLevel());
                break;
            case PACKAGE:
                Assert.assertEquals(expected, mojo.getPackageStabilityLevel());
                break;
            case CONFIG:
                Assert.assertEquals(expected, mojo.getConfigStabilityLevel());
                break;
        }
    }

    @Test
    public void testInvalidOptions() throws Exception {
        for (Boolean option : OPTIONS) {
            for (Boolean override : OVERRIDES) {
                doTestInvalidOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.MINIMUM);
                doTestInvalidOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.MINIMUM);

                doTestInvalidOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.CONFIG);
                doTestInvalidOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.CONFIG);

                doTestInvalidOption(new WfFeaturePackBuildMojo(), option, override, StabilityOption.PACKAGE);
                doTestInvalidOption(new UserFeaturePackBuildMojo(), option, override, StabilityOption.PACKAGE);

                doTestInvalidMix(new WfFeaturePackBuildMojo(), option, override);
                doTestInvalidMix(new UserFeaturePackBuildMojo(), option, override);

                doTestInvalidPackage(new WfFeaturePackBuildMojo(), option, override);
                doTestInvalidPackage(new UserFeaturePackBuildMojo(), option, override);
            }
        }
    }

    void doTestInvalidPackage(AbstractFeaturePackBuildMojo mojo, boolean option, boolean override) throws Exception {
        WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        if (option) {
            mojo.minimumStabilityLevel = Stability.EXPERIMENTAL.toString();
            mojo.packageStabilityLevel = Stability.DEFAULT.toString();
            mojo.configStabilityLevel = Stability.COMMUNITY.toString();
        }
        if (override || !option) {
            builder.setMinimumStabilityLevel(Stability.EXPERIMENTAL.toString());
            builder.setPackageStabilityLevel(Stability.DEFAULT.toString());
            builder.setConfigStabilityLevel(Stability.COMMUNITY.toString());
        }
        expectFailure(mojo, builder.build());
    }

    void doTestInvalidOption(AbstractFeaturePackBuildMojo mojo, boolean option, boolean override, StabilityOption kind) throws Exception {
        WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        // Always set a valid minimum stability level
        if (option) {
            mojo.minimumStabilityLevel = Stability.EXPERIMENTAL.toString();
        }
        if (override || !option) {
            builder.setMinimumStabilityLevel(Stability.EXPERIMENTAL.toString());
        }
        switch (kind) {
            case MINIMUM:
                if (option) {
                    mojo.stabilityLevel = Stability.COMMUNITY.toString();
                    mojo.minimumStabilityLevel = Stability.DEFAULT.toString();
                }
                if (override || !option) {
                    builder.setStabilityLevel(Stability.COMMUNITY.toString());
                    builder.setMinimumStabilityLevel(Stability.DEFAULT.toString());
                }
                break;
            case PACKAGE:
                if (option) {
                    mojo.stabilityLevel = Stability.COMMUNITY.toString();
                    mojo.packageStabilityLevel = Stability.COMMUNITY.toString();
                }
                if (override || !option) {
                    builder.setStabilityLevel(Stability.COMMUNITY.toString());
                    builder.setPackageStabilityLevel(Stability.COMMUNITY.toString());
                }
                break;
            case CONFIG:
                if (option) {
                    mojo.stabilityLevel = Stability.COMMUNITY.toString();
                    mojo.configStabilityLevel = Stability.COMMUNITY.toString();
                    // Needed, package must imply config.
                    mojo.packageStabilityLevel = Stability.EXPERIMENTAL.toString();
                }
                if (override || !option) {
                    builder.setStabilityLevel(Stability.COMMUNITY.toString());
                    builder.setConfigStabilityLevel(Stability.COMMUNITY.toString());
                    // Needed, package must imply config.
                    builder.setPackageStabilityLevel(Stability.EXPERIMENTAL.toString());
                }
                break;
        }
        expectFailure(mojo, builder.build());
    }

    void doTestInvalidMix(AbstractFeaturePackBuildMojo mojo, boolean option, boolean pkg) throws Exception {
        mojo.minimumStabilityLevel = Stability.COMMUNITY.toString();
        WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        if (option) {
            mojo.stabilityLevel = Stability.COMMUNITY.toString();
            if (pkg) {
                builder.setPackageStabilityLevel(Stability.COMMUNITY.toString());
            } else {
                builder.setConfigStabilityLevel(Stability.COMMUNITY.toString());
            }
        } else {
            builder.setStabilityLevel(Stability.COMMUNITY.toString());
            if (pkg) {
                mojo.packageStabilityLevel = Stability.COMMUNITY.toString();
            } else {
                mojo.configStabilityLevel = Stability.COMMUNITY.toString();
            }
        }
        expectFailure(mojo, builder.build());
    }

    void expectFailure(AbstractFeaturePackBuildMojo mojo, WildFlyFeaturePackBuild config) throws Exception {
        try {
            mojo.setStability(config);
            throw new Exception("Should have failed");
        } catch (MojoExecutionException ex) {
            // XXX OK
        }
    }
}
