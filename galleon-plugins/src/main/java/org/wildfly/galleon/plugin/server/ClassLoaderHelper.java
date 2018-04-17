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
package org.wildfly.galleon.plugin.server;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.jboss.galleon.ProvisioningException;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ClassLoaderHelper {

    public static void close(URLClassLoader newCl) {
        if (newCl != null) {
            try {
                newCl.close();
            } catch (IOException ioex) {
                throw new IllegalStateException("Couldn't stop server because of " + ioex.getMessage(), ioex);
            }
        }
    }

    public static URLClassLoader prepareClassLoader(Path jbossHome, final ClassLoader originalCl, final URL... urls) {
        final List<URL> cp = new ArrayList<>();
        try {
            cp.addAll(Arrays.asList(urls));
            addJars(jbossHome, cp);
            if (!(originalCl instanceof URLClassLoader)) {
                throw new IllegalArgumentException("Expected a URLClassLoader");
            }
            cp.addAll(Arrays.asList(((URLClassLoader) originalCl).getURLs()));
            return new URLClassLoader(cp.toArray(new URL[cp.size()]), null);
        } catch (IOException ioex) {
            throw new IllegalStateException("Couldn't load jars", ioex);
        }
    }

    public static URLClassLoader prepareProvisioningClassLoader(Path jbossHome, final ClassLoader originalCl, final URL... urls) {
        final List<URL> cp = new ArrayList<>();
        try {
            cp.addAll(Arrays.asList(urls));
            addJars(jbossHome, cp);
            if (!(originalCl instanceof URLClassLoader)) {
                throw new IllegalArgumentException("Expected a URLClassLoader");
            }
            cp.addAll(Arrays.asList(((URLClassLoader) originalCl).getURLs()));
            return new URLClassLoader(cp.toArray(new URL[cp.size()]), ProvisioningException.class.getClassLoader());
        } catch (IOException ioex) {
            throw new IllegalStateException("Couldn't load jars", ioex);
        }
    }

    private static List<URL> addJars(Path dir, List<URL> urls) throws IOException {
        Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.getFileName().toString().endsWith(".jar")) {
                    return FileVisitResult.CONTINUE;
                }
                urls.add(file.toUri().toURL());
                return FileVisitResult.CONTINUE;
            }
        });
        return urls;
    }
}
