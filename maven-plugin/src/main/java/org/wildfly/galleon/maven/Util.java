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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.Errors;

/**
 *
 * @author Alexey Loubyansky
 */
class Util {

    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(File configDir, String configFile) throws MojoExecutionException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if(!Files.exists(path)) {
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
}
