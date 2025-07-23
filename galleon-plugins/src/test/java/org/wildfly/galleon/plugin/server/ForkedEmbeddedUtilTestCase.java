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
package org.wildfly.galleon.plugin.server;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author jdenise
 */
public class ForkedEmbeddedUtilTestCase {

    @Test
    public void testParseException() throws Exception {
        {
           List<String> lst = new ArrayList<>();
           lst.add("org.wildfly.galleon.plugin.server.ConfigGeneratorException: Failed to start embedded server");
           lst.add(" at org.wildfly.galleon.plugin.config.generator.BaseConfigGenerator.doStartServer(BaseConfigGenerator.java:219)");
           lst.add("Caused by: org.wildfly.galleon.plugin.server.ConfigGeneratorException: java.lang.reflect.InvocationTargetException");
           lst.add(" at org.wildfly.galleon.plugin.config.generator.ServerBridge.embed_createStandalone(ServerBridge.java:236)");
           lst.add("Caused by: java.lang.reflect.InvocationTargetException");
           lst.add(" at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:118)");
           lst.add("Caused by: java.lang.RuntimeException: WFLYEMB0014: Cannot load module org.wildfly.embedded from: local module loader @78067cc4 (finder: local module finder @2183db30 (roots: /wldfly-core/modules/system/layers/base))");
           Throwable thr = ForkedEmbeddedUtil.parseException(lst, 0);
           Assert.assertEquals(ConfigGeneratorException.class.getName(), thr.getClass().getName());
           Assert.assertEquals("Failed to start embedded server", thr.getMessage());
           Assert.assertEquals(1, thr.getStackTrace().length);
           Assert.assertEquals(219, thr.getStackTrace()[0].getLineNumber());
           Assert.assertEquals("doStartServer", thr.getStackTrace()[0].getMethodName());
           Assert.assertEquals("org.wildfly.galleon.plugin.config.generator.BaseConfigGenerator", thr.getStackTrace()[0].getClassName());
           Assert.assertEquals("BaseConfigGenerator.java", thr.getStackTrace()[0].getFileName());

           Throwable cause1 = thr.getCause();
           Assert.assertEquals(ConfigGeneratorException.class.getName(), cause1.getClass().getName());
           Assert.assertEquals("java.lang.reflect.InvocationTargetException", cause1.getMessage());
           Assert.assertEquals(1, cause1.getStackTrace().length);
           Assert.assertEquals(236, cause1.getStackTrace()[0].getLineNumber());
           Assert.assertEquals("embed_createStandalone", cause1.getStackTrace()[0].getMethodName());
           Assert.assertEquals("org.wildfly.galleon.plugin.config.generator.ServerBridge", cause1.getStackTrace()[0].getClassName());
           Assert.assertEquals("ServerBridge.java", cause1.getStackTrace()[0].getFileName());

           Throwable cause2 = cause1.getCause();
           Assert.assertEquals(InvocationTargetException.class.getName(), cause2.getClass().getName());
           Assert.assertNull(cause2.getMessage());
           Assert.assertEquals(1, cause2.getStackTrace().length);
           Assert.assertEquals(118, cause2.getStackTrace()[0].getLineNumber());
           Assert.assertEquals("invoke", cause2.getStackTrace()[0].getMethodName());
           Assert.assertEquals("java.base/jdk.internal.reflect.DirectMethodHandleAccessor", cause2.getStackTrace()[0].getClassName());
           Assert.assertEquals("DirectMethodHandleAccessor.java", cause2.getStackTrace()[0].getFileName());

           Throwable cause3 = cause2.getCause();
           Assert.assertEquals(RuntimeException.class.getName(), cause3.getClass().getName());
           Assert.assertEquals("WFLYEMB0014: Cannot load module org.wildfly.embedded from: local module loader @78067cc4 (finder: local module finder @2183db30 (roots: /wldfly-core/modules/system/layers/base))", cause3.getMessage());
           Assert.assertEquals(0, cause3.getStackTrace().length);
        }
    }
}
