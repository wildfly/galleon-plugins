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
package org.wildfly.galleon.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.jboss.galleon.util.IoUtils;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer;
import org.wildfly.galleon.plugin.transformer.TransformedArtifact;

/**
 *
 * @author jdenise
 */
public class JakartaTransformation {

    private final boolean jakartaTransform;
    private final boolean jakartaTransformVerbose;
    private final Path jakartaTransformConfigsDir;
    private final Path jakartaTransformMavenRepo;
    private final Set<String> transformExcluded = new TreeSet<>();
    private final String jakartaTransformSuffix;
    private final Pattern excludedArtifactPattern;
    private final Set<Artifact> transformed = new HashSet<>();
    private final JakartaTransformer.LogHandler logHandler;
    private final Log log;

    JakartaTransformation(Log log, boolean jakartaTransform, boolean jakartaTransformVerbose,
            File jakartaTransformConfigsDir, File jakartaTransformRepo, String jakartaTransformSuffix,
            List<String> jakartaTransformExcludedArtifacts) {
        this.log = log;
        this.jakartaTransform = jakartaTransform;
        this.jakartaTransformVerbose = jakartaTransformVerbose;
        this.jakartaTransformConfigsDir = jakartaTransformConfigsDir == null ? null : jakartaTransformConfigsDir.toPath();
        this.jakartaTransformMavenRepo = jakartaTransformRepo.toPath();
        this.jakartaTransformSuffix = jakartaTransformSuffix;
        this.excludedArtifactPattern = (jakartaTransformExcludedArtifacts == null
                || jakartaTransformExcludedArtifacts.isEmpty()) ? null
                : Pattern.compile(toExcludedPattern(jakartaTransformExcludedArtifacts));
        JakartaTransformer.LogHandler logHandler = null;
        if (jakartaTransform) {
            logHandler = new JakartaTransformer.LogHandler() {
                @Override
                public void print(String format, Object... args) {
                    log.info(String.format(format, args));
                }
            };
            IoUtils.recursiveDelete(jakartaTransformMavenRepo);
        }
        this.logHandler = logHandler;
    }

    private boolean isAlreadyTransformed(Artifact a) {
        return transformed.contains(a);
    }

    boolean isJakartaTransformEnabled() {
        return jakartaTransform;
    }

    boolean transform(Artifact artifact) throws MojoExecutionException, IOException {
        if (isAlreadyTransformed(artifact)) {
            return false;
        }
        String grpid = artifact.getGroupId().replaceAll("\\.", Matcher.quoteReplacement(File.separator));
        Path grpidPath = getJakartaTransformMavenRepo().resolve(grpid);
        Path artifactidPath = grpidPath.resolve(artifact.getArtifactId());
        Path versionPath = artifactidPath.resolve(artifact.getVersion());
        Files.createDirectories(versionPath);
        boolean isTransformed = transform(artifact, versionPath);
        if (isTransformed && jakartaTransformSuffix != null) {
            // Copy a renamed version that will be used for future provisioning.
            Path transformedVersionPath = artifactidPath.resolve(artifact.getVersion() + jakartaTransformSuffix);
            Files.createDirectories(transformedVersionPath);
            String fileName = WfInstallPlugin.getTransformedArtifactFileName(artifact.getVersion(),
                    artifact.getFile().toPath().getFileName().toString(), jakartaTransformSuffix);
            Files.copy(versionPath.resolve(artifact.getFile().toPath().getFileName()),
                    transformedVersionPath.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
        return isTransformed;
    }

    private boolean transform(Artifact artifact, Path target) throws MojoExecutionException, IOException {
        if (isAlreadyTransformed(artifact)) {
            return false;
        }
        boolean isTransformed;
        if (isExcludedFromTransformation(artifact)) {
            transformExcluded.add(ArtifactCoords.newGav(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getVersion()).toString());
            isTransformed = false;
            Path srcPath = artifact.getFile().toPath();
            // Excluded artifact could not exist in remote repository, we need to copy it in the local cache.
            // Local cache is being used when the server is started to generate features.
            Files.copy(srcPath, target.resolve(srcPath.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        } else {
            TransformedArtifact a = JakartaTransformer.transform(jakartaTransformConfigsDir,
                    artifact.getFile().toPath(), target, jakartaTransformVerbose, logHandler);
            isTransformed = a.isTransformed();
            if (!a.isTransformed()) {
                transformExcluded.add(ArtifactCoords.newGav(artifact.getGroupId(),
                        artifact.getArtifactId(), artifact.getVersion()).toString());
            }
        }
        transformed.add(artifact);
        return isTransformed;
    }

    Path getJakartaTransformMavenRepo() {
        return jakartaTransformMavenRepo;
    }

    void writeExcludedArtifactsFile(Path wildflyResourcesDir) throws MojoExecutionException {
        if (!transformExcluded.isEmpty()) {
            try {
                Path excludedFilePath = wildflyResourcesDir.resolve(WfConstants.WILDFLY_JAKARTA_TRANSFORM_EXCLUDES);
                Util.mkdirs(wildflyResourcesDir);
                StringBuilder builder = new StringBuilder();
                for (String s : transformExcluded) {
                    builder.append(s).append(System.lineSeparator());
                }
                Files.write(excludedFilePath, builder.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
            } catch (IOException ex) {
                throw new MojoExecutionException(ex.getMessage(), ex);
            }
        }
    }

    private boolean isExcludedFromTransformation(Artifact artifact) throws MojoExecutionException {
        if (excludedArtifactPattern != null) {
            try {
                Matcher matchArtifact = excludedArtifactPattern.matcher(artifact.getGroupId() + ":" + artifact.getArtifactId());
                if (matchArtifact.find()) {
                    log.info("EE9: excluded " + artifact.getGroupId() + ":" + artifact.getArtifactId());
                    return true;
                }
            } catch (PatternSyntaxException e) {
                throw new MojoExecutionException("Invalid exclusion pattern: " + e.getMessage(), e);
            }
        }
        return false;
    }

    private static String toExcludedPattern(List<String> patterns) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < patterns.size(); i++) {
            builder.append(patterns.get(i));
            if (i < patterns.size() - 1) {
                builder.append("|");
            }
        }
        return builder.toString();
    }
}
