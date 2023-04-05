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
import org.wildfly.galleon.plugin.config.CopyArtifact;

/**
 *
 * @author Alexey Loubyansky
 */
public class Utils {

    private static final String EXPRESSION_PREFIX = "${";
    private static final String EXPRESSION_SUFFIX = "}";
    private static final String EXPRESSION_ENV_VAR = "env.";
    private static final String EXPRESSION_DEFAULT_VALUE_SEPARATOR = ":";
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

    public static boolean containsArtifact(Map<String, String> artifactsMap, MavenArtifact artifact) throws ProvisioningException {
        final StringBuilder key = new StringBuilder();
        final StringBuilder val = new StringBuilder();
        val.append(artifact.getGroupId()).append(":").append(artifact.getArtifactId()).append(":").append(artifact.getVersion()).append(":");
        key.append(artifact.getGroupId()).append(':').append(artifact.getArtifactId());
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            key.append("::").append(artifact.getClassifier());
            val.append(artifact.getClassifier());
        }
        val.append(":").append(artifact.getExtension());
        String value = artifactsMap.get(key.toString());
        return val.toString().equals(value);
    }

    public static MavenArtifact toArtifactCoords(Map<String, String> versionProps, String str, boolean optional,
            boolean channelArtifactResolution, boolean requireChannel) throws ProvisioningException {
        final MavenArtifact artifact = new MavenArtifact();
        if (requireChannel) {
            artifact.addMetadata(WfInstallPlugin.REQUIRES_CHANNEL_FOR_ARTIFACT_RESOLUTION_PROPERTY, "true");
        }
        artifact.setExtension(MavenArtifact.EXT_JAR);
        resolveArtifact(str, artifact, channelArtifactResolution);
        if(artifact.getGroupId() == null && artifact.getArtifactId() == null) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }

        if(!artifact.hasVersion()) {
            // The key is composed of groupId:artifactId[::classifier]
            String key = artifact.getGroupId() + ':' + artifact.getArtifactId() + ( artifact.getClassifier() == null || artifact.getClassifier().isEmpty() ?
                    "" : "::" + artifact.getClassifier() );
            final String resolvedStr = versionProps.get(key);
            if (resolvedStr == null) {
                if (optional) {
                    return null;
                }
                if (channelArtifactResolution) {
                    // No version defined in feature-pack, version would be retrieved from channel.
                    return artifact;
                } else {
                    throw new ProvisioningException("Failed to resolve the version of " + artifact.getGroupId() + ':' + artifact.getArtifactId());
                }
            }
            MavenArtifact resolvedArtifact = new MavenArtifact();
            resolveArtifact(resolvedStr, resolvedArtifact, channelArtifactResolution);
            if (!resolvedArtifact.hasVersion() && !channelArtifactResolution) {
                throw new ProvisioningException("Failed to resolve the version for artifact: " + resolvedStr);
            } else {
                artifact.setVersion(resolvedArtifact.getVersion());
            }
        }
        return artifact;
    }

    /**
     * Resolve an expression composed of ${a,b,c:defaultValue} Where a, b and c can be System properties or env.XXX env variables.
     */
    private static String resolveExpression(String coords, String str, boolean channelArtifactResolution, boolean isVersion) throws ProvisioningException {
        if (str == null) {
            return str;
        }
        String resolved = str;
        str = str.trim();
        if (str.startsWith(EXPRESSION_PREFIX) && str.endsWith(EXPRESSION_SUFFIX)) {
            String expressions = str.substring(EXPRESSION_PREFIX.length(), str.length() - EXPRESSION_SUFFIX.length());
            int defValueSeparator = expressions.indexOf(EXPRESSION_DEFAULT_VALUE_SEPARATOR);
            String defaultValue = null;
            if (defValueSeparator >= 0) {
                defaultValue = expressions.substring(defValueSeparator+1, expressions.length());
                defaultValue = defaultValue.trim();
                expressions = expressions.substring(0, defValueSeparator);
            }
            String[] split = expressions.split(",", -1);
            String value;
            for (String expression : split) {
                expression = expression.trim();
                if (expression.isEmpty()) {
                   throw new ProvisioningException("Invalid syntax for expression " + coords);
                }
                if (expression.startsWith(EXPRESSION_ENV_VAR)) {
                    expression = expression.substring(EXPRESSION_ENV_VAR.length(), expression.length());
                    if (expression.isEmpty()) {
                        throw new ProvisioningException("Invalid syntax for expression " + coords);
                    }
                    value = System.getenv(expression);
                    if (value != null) {
                        return value;
                    }
                } else {
                    value = System.getProperty(expression);
                    if (value != null) {
                        return value;
                    }
                }
            }
            if (defaultValue == null) {
                // Fail if not a version or if no channels have been configured
                if (!isVersion || !channelArtifactResolution ) {
                    throw new ProvisioningException("Unresolved expression for " + coords);
                }
            }
            resolved = defaultValue;
        }
        return resolved;
    }

    enum COORDS_STATE {
        GROUPID,
        ARTIFACTID,
        VERSION,
        CLASSIFIER,
        EXTENSION
    }

    static void resolveArtifact(String coords, MavenArtifact artifact, boolean channelArtifactResolution) throws ProvisioningException {
        if (coords == null) {
            return;
        }
        COORDS_STATE state = COORDS_STATE.GROUPID;
        StringBuilder currentBuilder = null;
        char[] array = coords.toCharArray();
        boolean expectSeparator = false;
        for (int i = 0; i < array.length; i++) {
            char c = array[i];
            if (c == ' ') {
                continue;
            }
            if (expectSeparator && c != ':') {
                throw new ProvisioningException("Invalid syntax for expression " + coords);
            }
            expectSeparator = false;
            if (c == '$') {
                if (i < array.length - 1) {
                    char next = array[i + 1];
                    if ('{' == next) {
                        // Expression
                        String remaining = coords.substring(i);
                        int end = remaining.indexOf("}");
                        if (end < 0) {
                            throw new ProvisioningException("Invalid syntax for expression " + coords);
                        }
                        String exp = remaining.substring(0, end + 1);
                        String resolvedExp = resolveExpression(coords, exp, channelArtifactResolution, state == COORDS_STATE.VERSION);
                        if (resolvedExp != null) {
                            if( currentBuilder == null) {
                                currentBuilder = new StringBuilder();
                            }
                            currentBuilder.append(resolvedExp);
                        }
                        expectSeparator = true;
                        i += end;
                    }
                } else {
                    if (currentBuilder == null) {
                        currentBuilder = new StringBuilder();
                    }
                    currentBuilder.append(c);
                }
            } else {
                if (c == ':') {
                    String current = currentBuilder == null ? null : currentBuilder.toString();
                    state = setState(coords, state, current, artifact);
                    currentBuilder = null;
                } else {

                    if (currentBuilder == null) {
                        currentBuilder = new StringBuilder();
                    }
                    currentBuilder.append(c);
                }
            }
        }
        setState(coords, state, currentBuilder == null ? null : currentBuilder.toString(), artifact);
    }

    private static COORDS_STATE setState(String coords, COORDS_STATE state, String value, MavenArtifact artifact) {
        COORDS_STATE newState = null;
        if(state == null) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + coords);
        }
        switch (state) {
            case GROUPID: {
                if (value != null) {
                    artifact.setGroupId(value);
                }
                newState = Utils.COORDS_STATE.ARTIFACTID;
                break;
            }
            case ARTIFACTID: {
                if (value != null) {
                    artifact.setArtifactId(value);
                }
                newState = Utils.COORDS_STATE.VERSION;
                break;
            }
            case VERSION: {
                if (value != null) {
                    artifact.setVersion(value);
                }
                newState = Utils.COORDS_STATE.CLASSIFIER;
                break;
            }
            case CLASSIFIER: {
                if (value != null) {
                    artifact.setClassifier(value);
                }
                newState = Utils.COORDS_STATE.EXTENSION;
                break;
            }
            case EXTENSION: {
                if (value != null) {
                    artifact.setExtension(value);
                }
                break;
            }
        }
        return newState;
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
        try (FileSystem zipFS = FileSystems.newFileSystem(artifact, (ClassLoader) null)) {
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

    static Map<String, String> toArtifactsMap(String str) throws ProvisioningException {
        if (str == null) {
            return Collections.emptyMap();
        }
        String[] split = str.split("\\|");
        Map<String, String> ret = new HashMap<>();
        for (String artifact : split) {
            //grpid:artifactId:version:[classifier]:extension
            // We could have overriden artifact with expression.
            MavenArtifact  mavenArtifact = new MavenArtifact();
            // We expect the extension.
            mavenArtifact.setExtension(null);
            resolveArtifact(artifact, mavenArtifact, false);
            StringBuilder builder = new StringBuilder();

            if (mavenArtifact.getGroupId() == null || mavenArtifact.getArtifactId() == null || !mavenArtifact.hasVersion() ||
                 mavenArtifact.getExtension() == null) {
                throw new IllegalArgumentException("Unexpected artifact coordinates format: " + artifact);
            }
            String grpId = check(artifact, mavenArtifact.getGroupId());
            String artifactId = check(artifact, mavenArtifact.getArtifactId());
            String version = check(artifact, mavenArtifact.getVersion());
            String classifier = mavenArtifact.getClassifier();
            if (classifier != null) {
                classifier = classifier.trim();
            }
            String ext = check(artifact,mavenArtifact.getExtension());
            String key = grpId + ":" + artifactId;
            builder.append(grpId).append(":").append(artifactId).append(":").append(version).append(":");
            if (classifier != null && !classifier.isEmpty()) {
                key = key + "::" + classifier;
                builder.append(classifier);
            }
            builder.append(":").append(ext);
            ret.put(key, builder.toString());
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
