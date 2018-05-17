/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;
import org.jboss.galleon.ProvisioningException;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 * @author Alexey Loubyansky
 */
public class FeatureSpecGeneratorInvoker {

    public static int generateSpecs(Path wildfly, Set<String> inheritedFeatures, Path outputDir, URL[] cpUrls, Log log) throws ProvisioningException {
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader newCl = new URLClassLoader(cpUrls, originalCl)) {
            if(log.isDebugEnabled()) {
                log.debug("Embedded server classpath:");
                printCl(newCl, log);
            }
            Thread.currentThread().setContextClassLoader(newCl);
            final Class<?> cliTest = newCl.loadClass("org.wildfly.galleon.plugin.featurespec.generator.FeatureSpecGenerator");
            final Method specGenMethod = cliTest.getMethod("generateSpecs", Path.class);
            return (int) specGenMethod.invoke(cliTest.getConstructor(Path.class, Set.class, boolean.class).newInstance(outputDir, inheritedFeatures, log.isDebugEnabled()), wildfly);
        } catch(InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if(cause instanceof ProvisioningException ) {
                throw (ProvisioningException) cause;
            }
            throw new ProvisioningException("Failed to generate feature specs", cause);
        } catch (Exception ex) {
            throw new ProvisioningException("Failed to generate feature specs", ex);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private static void printCl(ClassLoader cl, Log log) {
        if(cl.getParent() != null) {
            printCl(cl.getParent(), log);
        }
        log.debug(" " + cl.getClass().getName());
        if(!(cl instanceof URLClassLoader)) {
            log.debug("  NOT A URL CLASSLOADER");
            return;
        }
        int i = 0;
        for(URL url : ((URLClassLoader)cl).getURLs()) {
            log.debug("  " + ++i + ". " + url);
        }
    }

    private FeatureSpecGeneratorInvoker() {
    }
}
