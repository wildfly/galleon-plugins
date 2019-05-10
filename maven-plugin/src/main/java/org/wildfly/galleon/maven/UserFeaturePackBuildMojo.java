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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.galleon1.LegacyGalleon1Universe;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.galleon.plugin.ArtifactCoords;
import org.wildfly.galleon.plugin.WfConstants;

/**
 * This Maven Mojo is intended to be used to build feature-packs that depend on
 * one of the WildFly feature-packs. This Maven mojo creates a WildFly style feature-pack
 * archive from the provided resources according to the feature-pack build
 * configuration file and attaches it to the current Maven project as an
 * artifact. If no feature-pack build configuration is provided, some defaults
 * are applied.
 *
 * The content of the future feature-pack archive is first created in the
 * directory called `feature-pack-layout` under the module's build directory
 * which is then * ZIPped to create the feature-pack artifact.
 *
 * @author Jean-Francois Denise
 */
@Mojo(name = "build-user-feature-pack", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class UserFeaturePackBuildMojo extends AbstractFeaturePackBuildMojo {

    private static final String WILDFLY_GALLEON_PACK_PREFIX = "wildfly-";
    private static final String WILDFLY_GALLEON_PACK_SUFFIX = "galleon-pack";

    private static final String FEATURE_PACK_LAYOUT = "user-feature-pack-layout";

    /**
     * The feature-pack build configuration file.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-user-feature-pack-build.xml", property = "wildfly.user.feature.pack.configFile")
    private String configFile;

    /**
     * The feature-pack build configuration file directory
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.user.feature.pack.configDir")
    private File configDir;

    /**
     * Represents the directory containing child directories {@code packages},
     * {@code feature_groups}, {@code modules} etc. Either an absolute path or a
     * path relative to {@link #configDir}.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.user.feature.pack.resourcesDir", required = true)
    private String resourcesDir;

    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.user.feature.pack.buildName")
    private String buildName;

    /**
     * The FPL for the generated feature-pack.
     */
    @Parameter(alias = "feature-pack-location", defaultValue = "${project.groupId}:${project.artifactId}:${project.version}", required = false)
    private String fpLocation;

    private WildFlyFeaturePackBuild buildConfig;
    private boolean generate;

    @Override
    protected void doExecute() throws MojoExecutionException, MojoFailureException {

        final Path resources = configDir.getAbsoluteFile().toPath().resolve(resourcesDir);
        setupDirs(buildName, project.getArtifactId(), FEATURE_PACK_LAYOUT, resources);

        // feature-pack build config
        buildConfig = getBuildConfig();

        FeaturePackLocation fpl = buildConfig.getProducer();
        // In case we didn't generated the config and the producer has no version.
        // If FP is a maven artifact, we don't have an Universe, set the version.
        if (!generate && !fpl.hasBuild() && !fpl.hasUniverse()) {
            fpl = FeaturePackLocation.fromString(fpl.toString() + ":" + project.getVersion());
        }
        // feature-pack builder
        final FeaturePackDescription.Builder fpBuilder = FeaturePackDescription.builder(FeaturePackSpec.builder(fpl.getFPID()));

        for (String defaultPackage : buildConfig.getDefaultPackages()) {
            fpBuilder.getSpecBuilder().addDefaultPackage(defaultPackage);
        }

        try {
            processFeaturePackDependencies(buildConfig, fpBuilder.getSpecBuilder());
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to process dependencies", e);
        }

        final Path srcModulesDir = resources.resolve(WfConstants.MODULES);
        if (Files.exists(srcModulesDir)) {
            addModulePackages(srcModulesDir, fpBuilder, resources);
        }

        buildFeaturePack(fpBuilder, buildConfig);
    }

    private void addModulePackages(final Path srcModulesDir, final FeaturePackDescription.Builder fpBuilder, final Path targetResources) throws MojoExecutionException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        try {
            Util.findModules(srcModulesDir, moduleXmlByPkgName);
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, null);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
    }

    private WildFlyFeaturePackBuild getBuildConfig() throws MojoExecutionException {
        if (buildConfig == null) {
            final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
            if (Files.exists(path)) {
                buildConfig = Util.loadFeaturePackBuildConfig(path);
            } else {
                buildConfig = generateConfig();
            }
        }
        return buildConfig;
    }

    private WildFlyFeaturePackBuild generateConfig() throws MojoExecutionException {
        generate = true;
        if (fpLocation == null) {
            throw new MojoExecutionException("No feature-pack-location set");
        }
        WildFlyFeaturePackBuild.Builder builder = WildFlyFeaturePackBuild.builder();
        builder.setProducer(FeaturePackLocation.fromString(fpLocation));
        Map<FeaturePackLocation, Artifact> directs = retrieveDirectDependencies();
        if (directs.isEmpty()) {
            throw new MojoExecutionException("No dependency on WildFly feature-pack retrieved. "
                    + "WildFly feature-pack must be a dependency of the project.");
        }
        Map<FeaturePackLocation, org.apache.maven.artifact.Artifact> transitives = retrieveTransitiveDependencies(directs.keySet());
        for (Entry<FeaturePackLocation, Artifact> entry : directs.entrySet()) {
            FeaturePackConfig.Builder depBuilder = FeaturePackConfig.builder(entry.getKey());
            depBuilder.setInheritConfigs(false);
            depBuilder.setInheritPackages(false);
            Artifact a = entry.getValue();
            debug("Adding %s:%s dependency", a.getGroupId(), a.getArtifactId());
            builder.addDependency(ArtifactCoords.newGav(a.getGroupId(), a.getArtifactId(), null),
                    FeaturePackDependencySpec.create(a.getGroupId() + ":" + a.getArtifactId(), depBuilder.build()));
        }
        for (Entry<FeaturePackLocation, org.apache.maven.artifact.Artifact> entry : transitives.entrySet()) {
            FeaturePackConfig.Builder depBuilder = FeaturePackConfig.transitiveBuilder(entry.getKey());
            org.apache.maven.artifact.Artifact a = entry.getValue();
            debug("Adding %s:%s transitive dependency", a.getGroupId(), a.getArtifactId());
            builder.addDependency(ArtifactCoords.newGav(a.getGroupId(), a.getArtifactId(), null),
                    FeaturePackDependencySpec.create(a.getGroupId() + ":" + a.getArtifactId(), depBuilder.build()));
        }
        return builder.build();
    }

    private Map<FeaturePackLocation, Artifact> retrieveDirectDependencies() {
        Map<FeaturePackLocation, Artifact> directs = new HashMap<>();
        final ArtifactDescriptorRequest descrReq = new ArtifactDescriptorRequest();
        descrReq.setArtifact(new org.eclipse.aether.artifact.DefaultArtifact(project.getGroupId(),
                project.getArtifactId(), null, project.getVersion()));
        try {
            ArtifactDescriptorResult res = repoSystem.readArtifactDescriptor(session.getRepositorySession(), descrReq);
            for (Dependency d : res.getDependencies()) {
                FeaturePackLocation fpl = getFeaturePackLocation(d.getArtifact().getGroupId(),
                        d.getArtifact().getArtifactId(), d.getArtifact().getVersion(), d.getArtifact().getExtension(), d.getScope());
                if (fpl != null) {
                    directs.put(fpl, d.getArtifact());
                }
            }
        } catch (ProvisioningException | ArtifactDescriptorException | IOException ex) {
            throw new RuntimeException(ex);
        }
        return directs;
    }

    private Map<FeaturePackLocation, org.apache.maven.artifact.Artifact> retrieveTransitiveDependencies(Set<FeaturePackLocation> directs) {
        Map<FeaturePackLocation, org.apache.maven.artifact.Artifact> transitives = new HashMap<>();
        try {
            for (org.apache.maven.artifact.Artifact a : project.getArtifacts()) {
                FeaturePackLocation fpl = getFeaturePackLocation(a.getGroupId(),
                        a.getArtifactId(), a.getVersion(), a.getType(), a.getScope());
                if (fpl != null && !directs.contains(fpl)) {
                    transitives.put(fpl, a);
                }
            }
        } catch (ProvisioningException | IOException ex) {
            throw new RuntimeException(ex);
        }
        return transitives;
    }

    private FeaturePackLocation getFeaturePackLocation(String groupId, String artifactId,
            String version, String ext, String scope) throws IOException, ProvisioningException {
        FeaturePackLocation fpl = null;
        if (artifactId.startsWith(WILDFLY_GALLEON_PACK_PREFIX) && artifactId.endsWith(WILDFLY_GALLEON_PACK_SUFFIX)) {
            if (ext.equals("zip") && (!"test".equals(scope)) && (!"system".equals(scope))) {
                try (FileSystem fs = ZipUtils.newFileSystem(resolveArtifact(new ArtifactCoords(groupId,
                        artifactId, version, null, "zip")))) {
                    if (Files.exists(fs.getPath(Constants.FEATURE_PACK_XML))) {
                        fpl = LegacyGalleon1Universe.toFpl(groupId,
                                artifactId, null);
                    }
                }
            }
        }
        return fpl;
    }
}
