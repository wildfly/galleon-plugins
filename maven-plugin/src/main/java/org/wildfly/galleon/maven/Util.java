/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.Errors;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
class Util {

    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(File configDir, String configFile) throws MojoExecutionException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if (!Files.exists(path)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(path));
        }
        return loadFeaturePackBuildConfig(path);
    }

    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(Path configFile) throws MojoExecutionException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            return new FeaturePackBuildModelParser().parse(configStream);
        } catch (XMLStreamException e) {
            throw new MojoExecutionException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.openFile(configFile), e);
        }
    }

    static void mkdirs(final Path dir) throws MojoExecutionException {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(dir), e);
        }
    }

    static void copyIfExists(final Path resources, final Path fpDir, String resourceName) throws MojoExecutionException {
        final Path res = resources.resolve(resourceName);
        if (Files.exists(res)) {
            try {
                IoUtils.copy(res, fpDir.resolve(resourceName));
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to copy " + resourceName + " to the feature-pack", e);
            }
        }
    }

    static void copyDirIfExists(final Path srcDir, final Path targetDir) throws MojoExecutionException {
        if (Files.exists(srcDir)) {
            try {
                IoUtils.copy(srcDir, targetDir);
            } catch (IOException e) {
                throw new MojoExecutionException(Errors.copyFile(srcDir, targetDir), e);
            }
        }
    }

    static void findModules(Path modulesDir, Map<String, Path> moduleXmlByPkgName) throws IOException {
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if (!Files.exists(moduleXml)) {
                    return FileVisitResult.CONTINUE;
                }

                String packageName;
                if (moduleXml.getParent().getFileName().toString().equals("main")) {
                    packageName = modulesDir.relativize(moduleXml.getParent().getParent()).toString();
                } else {
                    packageName = modulesDir.relativize(moduleXml.getParent()).toString();
                }
                packageName = packageName.replace(File.separatorChar, '.');
                moduleXmlByPkgName.put(packageName, moduleXml);
                return FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
