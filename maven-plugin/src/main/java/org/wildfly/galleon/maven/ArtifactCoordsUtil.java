/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;

import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 *
 * @author Alexey Loubyansky
 */
public class ArtifactCoordsUtil {

    public static ArtifactCoords fromJBossModules(String str, String extension) {
        final String[] parts = str.split(":");
        if(parts.length < 2) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = null;
        String classifier = null;
        if(parts.length > 2) {
            if(!parts[2].isEmpty()) {
                version = parts[2];
            }
            if(parts.length > 3) {
                if(!parts[3].isEmpty()) {
                    classifier = parts[3];
                }
                if(parts.length > 4) {
                    if(!parts[4].isEmpty()) {
                        extension = parts[4];
                    }
                    if (parts.length > 5) {
                        throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
                    }
                }
            }
        }
        return new ArtifactCoords(groupId, artifactId, version, classifier, extension);
    }
}
