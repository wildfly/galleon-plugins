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

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
/**
 *
 * @author jdenise
 */
public class UtilsTestCase {

    @Test
    public void testOverriddenArtifacts() throws Exception {
        {
            String str = "grp:art:vers:class:jar";
            Map<String, String> map = getGlobalScope(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(str, value);
        }

        {
            String str = "grp:art:vers::jar";
            Map<String, String> map = getGlobalScope(str);
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
                Map<String, String> map = getGlobalScope(str);
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
                    Map<String, String> map = getGlobalScope(str);
                    throw new Exception("Should have failed");
                } catch (IllegalArgumentException ex) {
                    // XXX OK expected
                }
            }
        }
    }

    @Test
    public void testOverriddenArtifactsProducers() throws Exception {
        {
            String artifacts = "grp:art:vers:class:jar";
            String str = "@foo=" + artifacts;
            Map<String, String> map = getProducerScope(str, "foo");
            Assert.assertEquals(1, map.size());
            String key = "grp:art::class";
            String value = map.get(key);
            Assert.assertEquals(artifacts, value);
        }

        {
            String global = "grp:art:vers::jar";
            String producer1 = "foo";
            String producer1Artifact = "grp:art:vers2::jar";
            String producer2 = "bar";
            String producer2Artifact1 = "grp2:art2:vers::jar";
            String producer2Artifact2 = "grp2:art3:vers::jar";
            String str = global +"@"+producer1+"="+producer1Artifact +"@"+producer2+"="+producer2Artifact1 + "|"+producer2Artifact2 ;
            Map<String, String> map = getGlobalScope(str);
            Assert.assertEquals(1, map.size());
            String key = "grp:art";
            String value = map.get(key);
            Assert.assertEquals(global, value);

            Map<String, String> map2 = getProducerScope(str, producer1);
            Assert.assertEquals(1, map2.size());
            String key2 = "grp:art";
            String value2 = map2.get(key2);
            Assert.assertEquals(producer1Artifact, value2);

            Map<String, String> map3 = getProducerScope(str, producer2);
            Assert.assertEquals(2, map3.size());
            String key3 = "grp2:art2";
            String value3 = map3.get(key3);
            Assert.assertEquals(producer2Artifact1, value3);
            String key4 = "grp2:art3";
            String value4 = map3.get(key4);
            Assert.assertEquals(producer2Artifact2, value4);
        }
    }

    private static Map<String, String> getGlobalScope(String str) {
        return Utils.toArtifactsMap(str).get(WfInstallPlugin.GLOBAL_ARTIFACTS_KEY);
    }

    private static Map<String, String> getProducerScope(String str, String producer) {
        return Utils.toArtifactsMap(str).get(producer);
    }
}
