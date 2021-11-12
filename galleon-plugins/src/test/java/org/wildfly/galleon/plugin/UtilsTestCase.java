/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
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

import java.util.HashMap;
import java.util.Map;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author jdenise
 */
public class UtilsTestCase {

    @Test
    public void testBasic() throws Exception {
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "artId:grpId";
            String value = "artId:grpId:1.0.0.Final::jar";
            versionsProps.put(key, value);
            MavenArtifact artifact = Utils.toArtifactCoords(versionsProps, key, false);
            Assert.assertEquals("artId", artifact.getGroupId());
            Assert.assertEquals("grpId", artifact.getArtifactId());
            Assert.assertEquals("1.0.0.Final", artifact.getVersion());
            Assert.assertEquals("", artifact.getClassifier());
            Assert.assertEquals("jar", artifact.getExtension());
        }
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "artId:grpId";
            String value = "artId:grpId:1.0.0.Final";
            versionsProps.put(key, value);
            MavenArtifact artifact = Utils.toArtifactCoords(versionsProps, key, false);
            Assert.assertEquals("artId", artifact.getGroupId());
            Assert.assertEquals("grpId", artifact.getArtifactId());
            Assert.assertEquals("1.0.0.Final", artifact.getVersion());
            Assert.assertEquals("", artifact.getClassifier());
            Assert.assertEquals("jar", artifact.getExtension());
        }
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "artId:grpId::linux-x86_64";
            String value = "artId:grpId:1.0.0.Final:linux-x86_64:jar";
            versionsProps.put(key, value);
            MavenArtifact artifact = Utils.toArtifactCoords(versionsProps, key, false);
            Assert.assertEquals("artId", artifact.getGroupId());
            Assert.assertEquals("grpId", artifact.getArtifactId());
            Assert.assertEquals("1.0.0.Final", artifact.getVersion());
            Assert.assertEquals("linux-x86_64", artifact.getClassifier());
            Assert.assertEquals("jar", artifact.getExtension());
        }
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "artId:grpId";
            String value = "artId:grpId:1.0.0.Final::so";
            versionsProps.put(key, value);
            String lookupArtifact = "artId:grpId:::so";
            MavenArtifact artifact = Utils.toArtifactCoords(versionsProps, lookupArtifact, false);
            Assert.assertEquals("artId", artifact.getGroupId());
            Assert.assertEquals("grpId", artifact.getArtifactId());
            Assert.assertEquals("1.0.0.Final", artifact.getVersion());
            Assert.assertEquals("so", artifact.getExtension());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "artId:grpId::class";
            String value = "artId:grpId:1.0.0.Final:class:so";
            versionsProps.put(key, value);
            String lookupArtifact = "artId:grpId::class:so";
            MavenArtifact artifact = Utils.toArtifactCoords(versionsProps, lookupArtifact, false);
            Assert.assertEquals("artId", artifact.getGroupId());
            Assert.assertEquals("grpId", artifact.getArtifactId());
            Assert.assertEquals("1.0.0.Final", artifact.getVersion());
            Assert.assertEquals("class", artifact.getClassifier());
            Assert.assertEquals("so", artifact.getExtension());
        }
    }

    @Test
    public void testOverriddenArtifacts() throws Exception {
        {
            String str = "grp:art:vers:class:jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }

        {
            String str = "grp:art:vers::jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }

        {
            String str1 = "grp:art:vers::jar";
            String str2 = "grp2:art2:vers2:class2:jar2";
            String str3 = " grp3 : art3 : vers3:    : jar3  ";
            String str3Trimmed = "grp3:art3:vers3::jar3";
            String[] cases = {
                str1 + " | " + str2 + "|" + str3,
                str1 + "|" + str2 + "|" + str3,
                "  " + str1 + " | " + str2 + "   " + " | " + str3};
            for (String str : cases) {
                Map<String, String> map = Utils.toArtifactsMap(str);
                Assert.assertEquals(3, map.size());
                String key1 = "grp:art";
                String value1 = map.get(key1);
                Assert.assertEquals(str1, value1);
                String key2 = "grp2:art2::class2";
                String value2 = map.get(key2);
                Assert.assertEquals(str2, value2);
                String key3 = "grp3:art3";
                String value3 = map.get(key3);
                Assert.assertEquals(str3Trimmed, value3);
            }
        }

        {
            String[] invalids = {
                "",
                "1:2:3:4:5:6",
                "a:b:c:d",
                ":b:c:d:e",
                "a::c:d:e",
                "a:b::d:e",
                "a:b:c::",
                "a:b:c:d:",
                "a:b:c:d:e|a:b:c:d:",
                " : : : : ",
                "a:b:c:d:e| :b:c:d:  "};
            for (String str : invalids) {
                try {
                    Map<String, String> map = Utils.toArtifactsMap(str);
                    throw new Exception("Should have failed " + str);
                } catch (IllegalArgumentException ex) {
                    // XXX OK expected
                }
            }
        }

        {
            String str = "grp:art:${a,b:c}:class:jar";
            String resolved = "grp:art:c:class:jar";
            Map<String, String> map = Utils.toArtifactsMap(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(resolved, value);
        }
    }

    @Test
    public void testVersionExpression() throws Exception {
        String envVar = "WFGP_TEST_VERSION";
        String env = "env." + envVar;
        String envVersionValue = System.getenv(envVar);

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String v = "123";
            String val = "a:b:" + v + "::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(v, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(v, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            String prop = "org.wfgp.version";
            String versionValue = "9999";
            String key = "a:b";
            String val = "a:b:${" + prop + "}::jar";
            System.setProperty(prop, versionValue);
            Map<String, String> versionsProps = Utils.toArtifactsMap(val);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "010101";
            String val = "a:b:${" + prop + ":" + defaultValue + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = ":01:0101:";
            String val = "a:b:${" + prop + ":" + defaultValue + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "010101";
            String val = "a:b:  ${" + prop + ":" + defaultValue + "}  ::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${" + prop + ":}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(defaultValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String val = "a:b:${" + prop + ",:}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "foo";
            String val = "a:b:${,,:   " + defaultValue + "   }::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${:}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false).getVersion();
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false).getVersion();
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String defaultValue = "";
            String val = "a:b:${,,,,,,,,,,:}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${" + env + "}::jar";
            versionsProps.put(key, val);
            Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
            Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String versionValue = "0000";
            String key = "a:b";
            String val = "a:b:${" + prop + "," + env + "}::jar";
            versionsProps.put(key, val);
            System.setProperty(prop, versionValue);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String versionValue = "0000";
            String key = "a:b";
            String val = "a:b:${" + env + "," + prop + "}::jar";
            versionsProps.put(key, val);
            System.setProperty(prop, versionValue);
            try {
                Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(envVersionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop1 = "org.wfgp.version1";
            String prop2 = "org.wfgp.version2";
            String versionValue = "5555";
            String key = "a:b";
            String val = "a:b:${ " + prop1 + " , " + prop2 + " }::jar";
            versionsProps.put(key, val);
            System.setProperty(prop2, versionValue);
            try {
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, key, false).getVersion());
                Assert.assertEquals(versionValue, Utils.toArtifactCoords(versionsProps, val, false).getVersion());
            } finally {
                System.clearProperty(prop2);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String prop = "org.wfgp.version";
            String key = "a:b";
            String val = "a:b:${" + prop + "}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String unknownEnv = "env.WFGP_FOO";
            String key = "a:b";
            String val = "a:b:${" + unknownEnv + "}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Unresolved expression for " + val, ex.getMessage());
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${env.:foo}::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }
    }

    @Test
    public void testExpression() throws Exception {
        String envVar = "WFGP_TEST_VERSION";
        String env = "env." + envVar;
        String envVersionValue = System.getenv(envVar);

        {
            String val = "a:b:c:d:e";
            MavenArtifact artifact = new MavenArtifact();
            Utils.resolveArtifact(val, artifact);
            Assert.assertEquals("a", artifact.getGroupId());
            Assert.assertEquals("b", artifact.getArtifactId());
            Assert.assertEquals("c", artifact.getVersion());
            Assert.assertEquals("d", artifact.getClassifier());
            Assert.assertEquals("e", artifact.getExtension());
        }

        {
            String prop1 = "org.wfgp.grpid";
            String prop2 = "org.wfgp.artid";
            String prop3 = "org.wfgp.version";
            String prop4 = "org.wfgp.classifier";
            String prop5 = "org.wfgp.ext";
            String val1 = "org.foo.bar";
            String val2 = "babar";
            String val3 = "1.0.Final";
            String val4 = "foo";
            String val5 = "dd";
            String val = "${" + prop1 + "}:${" + prop2 + "}:${" + prop3 + "}:${" + prop4 + "}:${" + prop5 + "}";
            System.setProperty(prop1, val1);
            System.setProperty(prop2, val2);
            System.setProperty(prop3, val3);
            System.setProperty(prop4, val4);
            System.setProperty(prop5, val5);
            try {
                MavenArtifact artifact = new MavenArtifact();
                Utils.resolveArtifact(val, artifact);
                Assert.assertEquals(val1, artifact.getGroupId());
                Assert.assertEquals(val2, artifact.getArtifactId());
                Assert.assertEquals(val3, artifact.getVersion());
                Assert.assertEquals(val4, artifact.getClassifier());
                Assert.assertEquals(val5, artifact.getExtension());
            } finally {
                System.clearProperty(prop1);
                System.clearProperty(prop2);
                System.clearProperty(prop3);
                System.clearProperty(prop4);
                System.clearProperty(prop5);
            }
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${env.BAR:foo::jar";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (ProvisioningException ex) {
                // XXX OK expected
                Assert.assertEquals("Invalid syntax for expression " + val, ex.getMessage());
            }
        }
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${env.BAR:foo}::$";
            versionsProps.put(key, val);
            MavenArtifact artifact = new MavenArtifact();
            Utils.resolveArtifact(val, artifact);
            Assert.assertEquals("a", artifact.getGroupId());
            Assert.assertEquals("b", artifact.getArtifactId());
            Assert.assertEquals("foo", artifact.getVersion());
            Assert.assertEquals("", artifact.getClassifier());
            Assert.assertEquals("$", artifact.getExtension());
        }
        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "::::";
            versionsProps.put(key, val);
            MavenArtifact artifact = new MavenArtifact();
            artifact.setExtension(null);
            Utils.resolveArtifact(val, artifact);
            Assert.assertNull(artifact.getGroupId());
            Assert.assertNull(artifact.getArtifactId());
            Assert.assertNull(artifact.getVersion());
            Assert.assertEquals("", artifact.getClassifier());
            Assert.assertNull(artifact.getExtension());
        }

        {
            Map<String, String> versionsProps = new HashMap<>();
            String key = "a:b";
            String val = "a:b:${env.BAR:foo}::jar:";
            versionsProps.put(key, val);
            try {
                Utils.toArtifactCoords(versionsProps, key, false);
                throw new Exception("Should have failed");
            } catch (IllegalArgumentException ex) {
                // XXX OK expected
                Assert.assertEquals("Unexpected artifact coordinates format: " + val, ex.getMessage());
            }
            try {
                Utils.toArtifactCoords(versionsProps, val, false);
                throw new Exception("Should have failed");
            } catch (IllegalArgumentException ex) {
                // XXX OK expected
                Assert.assertEquals("Unexpected artifact coordinates format: " + val, ex.getMessage());
            }
        }
    }
}
