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
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.LayoutUtils;
import org.jboss.galleon.util.StringUtils;
import static org.wildfly.galleon.plugin.WfInstallPlugin.GLOBAL_ARTIFACTS_KEY;
import org.wildfly.galleon.plugin.config.CopyArtifact;

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

    public static boolean containsArtifact(Map<String, Map<String, String>> artifactsMap, MavenArtifact artifact, String producerName) throws ProvisioningException {
        final StringBuilder key = new StringBuilder();
        final StringBuilder val = new StringBuilder();
        val.append(artifact.getGroupId()).append(":").append(artifact.getArtifactId()).append(":").append(artifact.getVersion()).append(":");
        key.append(artifact.getGroupId()).append(':').append(artifact.getArtifactId());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            key.append("::").append(artifact.getClassifier());
            val.append(artifact.getClassifier());
        }
        val.append(":").append(artifact.getExtension());
        // Check first in global scope
        Map<String, String> global = artifactsMap.get(GLOBAL_ARTIFACTS_KEY);
        if (global != null) {
            String value = global.get(key.toString());
            if (val.toString().equals(value)) {
                return true;
            }
        }
        // Local to the feature-pack
        Map<String, String> local = artifactsMap.get(producerName);
        if (local != null) {
            String value = local.get(key.toString());
            if (val.toString().equals(value)) {
                return true;
            }
        }
        return false;
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

    public static List<Path> collectLayersConf(ProvisioningLayout<?> layout) throws ProvisioningException {
        List<Path> layersConfs = Collections.emptyList();
        for(FeaturePackLayout fp : layout.getOrderedFeaturePacks()) {
            Path p = LayoutUtils.getPackageContentDir(fp.getDir(), WfConstants.LAYERS_CONF);
            if(!Files.exists(p)) {
                continue;
            }
            p = p.resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF);
            if(!Files.exists(p)) {
                throw new ProvisioningException(
                        "Feature-pack " + fp.getFPID() + " package " + WfConstants.LAYERS_CONF + " is expected to contain "
                                + WfConstants.MODULES + "/" + WfConstants.LAYERS_CONF + " but it does not");
            }
            layersConfs = CollectionUtils.add(layersConfs, p);
        }
        return layersConfs;
    }

    public static void mergeLayersConfs(List<Path> layersConfs, Path distHome) throws ProvisioningException {
        if(layersConfs.isEmpty()) {
            return;
        }
        if(layersConfs.size() == 1) {
            try {
                IoUtils.copy(layersConfs.get(0), distHome.resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF));
            } catch (IOException e) {
                throw new ProvisioningException("Failed to install layes.conf to " + distHome, e);
            }
            return;
        }
        final Properties props = new Properties();
        final Set<String> layers = new LinkedHashSet<>(layersConfs.size());
        for(Path p : layersConfs) {
            try(BufferedReader reader = Files.newBufferedReader(p)) {
                props.load(reader);
            } catch (IOException e) {
                throw new ProvisioningException("Failed to generate layer.conf", e);
            }
            String layersProp = props.getProperty(WfConstants.LAYERS);
            if (layersProp == null || (layersProp = layersProp.trim()).length() == 0) {
                continue;
            }
            final String[] layerNames = layersProp.split(",");
            for(String layerName : layerNames) {
                layers.add(layerName);
            }
        }
        if(!layers.isEmpty()) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, layers);
            props.setProperty(WfConstants.LAYERS, buf.toString());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(distHome.resolve(WfConstants.MODULES).resolve(WfConstants.LAYERS_CONF))) {
            props.store(writer, "Generated by WildFly Galleon provisioning plugin");
        }
        catch (IOException e) {
            throw new ProvisioningException("Failed to persist generated layers.conf", e);
        }
    }

    public static void extractArtifact(Path artifact, Path target, CopyArtifact copy) throws IOException {
        if(!Files.exists(target)) {
            Files.createDirectories(target);
        }
        try (FileSystem zipFS = FileSystems.newFileSystem(artifact, null)) {
            for(Path zipRoot : zipFS.getRootDirectories()) {
                Files.walkFileTree(zipRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                                String entry = dir.toString().substring(1);
                                if(entry.isEmpty()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if(!entry.endsWith("/")) {
                                    entry += '/';
                                }
                                if(!copy.includeFile(entry)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                final Path targetDir = target.resolve(zipRoot.relativize(dir).toString());
                                try {
                                    Files.copy(dir, targetDir);
                                } catch (FileAlreadyExistsException e) {
                                     if (!Files.isDirectory(targetDir))
                                         throw e;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                                if(copy.includeFile(file.toString().substring(1))) {
                                    final Path targetPath = target.resolve(zipRoot.relativize(file).toString());
                                    Files.copy(file, targetPath);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            }
        }
    }

    static Map<String, Map<String, String>> toArtifactsMap(String str) {
        if (str == null) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, String>> ret = new HashMap<>();
        //a|b|c
        //@producer=a|b|c@producer=...
        if (str.startsWith("@")) {
            // Ignore first delimiter
            str = str.substring(1);
        }
        String[] producers = str.split("@");
        for (String producer : producers) {
            int eqIndex = producer.indexOf("=");
            String producerName = GLOBAL_ARTIFACTS_KEY;
            String producerArgs = producer;
            Map<String, String> artifactsMap;
            if (eqIndex > 0) {
                producerName = producer.substring(0, eqIndex);
                producerArgs = producer.substring(eqIndex+1);
            }
            artifactsMap = ret.get(producerName);
            if (artifactsMap == null) {
                artifactsMap = new HashMap<>();
                ret.put(producerName, artifactsMap);
            }
            String[] split = producerArgs.split("\\|");
            for (String artifact : split) {
                //grpid:artifactId:version:[classifier]:extension
                artifact = artifact.trim();
                StringBuilder builder = new StringBuilder();
                String[] parts = artifact.split(":");
                if (parts.length != 5) {
                    throw new IllegalArgumentException("Unexpected artifact coordinates format: " + artifact);
                }
                String grpId = check(artifact, parts[0]);
                String artifactId = check(artifact, parts[1]);
                String version = check(artifact, parts[2]);
                String classifier = parts[3];
                if (classifier != null) {
                    classifier = classifier.trim();
                }
                String ext = check(artifact, parts[4]);
                String key = grpId + ":" + artifactId;
                builder.append(grpId).append(":").append(artifactId).append(":").append(version).append(":");
                if (classifier != null && !classifier.isEmpty()) {
                    key = key + "::" + classifier;
                    builder.append(classifier);
                }
                builder.append(":").append(ext);
                artifactsMap.put(key, builder.toString());
            }
        }
        return ret;
    }

    private static String check(String artifact, String item) {
        if (item != null) {
            item = item.trim();
        }
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + artifact);
        }
        return item;
    }
}
