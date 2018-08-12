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

package org.wildfly.galleon.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;

/**
 *
 * @author Alexey Loubyansky
 */
public class Utils {

    public static void readProperties(Path propsFile, Map<String, String> propsMap) throws ProvisioningException {
        try(BufferedReader reader = Files.newBufferedReader(propsFile)) {
            String line = reader.readLine();
            while(line != null) {
                line = line.trim();
                if(line.charAt(0) != '#' && !line.isEmpty()) {
                    final int i = line.indexOf('=');
                    if(i < 0) {
                        throw new ProvisioningException("Failed to parse property " + line + " from " + propsFile);
                    }
                    propsMap.put(line.substring(0, i), line.substring(i + 1));
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(propsFile), e);
        }
    }

    public static Map<String, String> readProperties(final Path propsFile) throws ProvisioningException {
        final Map<String, String> propsMap = new HashMap<>();
        readProperties(propsFile, propsMap);
        return propsMap;
    }

    public static MavenArtifact toArtifactCoords(Map<String, String> versionProps, String str, boolean optional) throws ProvisioningException {
        String[] parts = str.split(":");
        if(parts.length < 2) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId(parts[0]);
        artifact.setArtifactId(parts[1]);
        artifact.setExtension(MavenArtifact.EXT_JAR);
        if(parts.length > 2) {
            if(!parts[2].isEmpty()) {
                artifact.setVersion(parts[2]);
            }
            if(parts.length > 3) {
                artifact.setClassifier(parts[3]);
                if(parts.length > 4 && !parts[4].isEmpty()) {
                    artifact.setExtension(parts[4]);
                    if (parts.length > 5) {
                        throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
                    }
                }
            }
        }

        if(!artifact.hasVersion()) {
            final String resolvedStr = versionProps.get(artifact.getGroupId() + ':' + artifact.getArtifactId());
            if (resolvedStr == null) {
                if (optional) {
                    return null;
                }
                throw new ProvisioningException("Failed to resolve the version of " + artifact.getGroupId() + ':' + artifact.getArtifactId());
            }
            parts = resolvedStr.split(":");
            if (parts.length < 3) {
                throw new ProvisioningException("Failed to resolve the version for artifact: " + resolvedStr);
            }
            artifact.setVersion(parts[2]);
        }
        return artifact;
    }
}
