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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import javax.xml.bind.DatatypeConverter;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.wildfly.galleon.plugin.ArtifactCoords;

/**
 * Generate Artifact list in offliner format: checksum,artifact path
 *
 * @author jdenise@redhat.com
 */
public class ArtifactListBuilder {

    private final Path localMvnRepoPath;
    private final Map<String, String> map = new TreeMap<>();
    private final MessageDigest md;

    private final MavenArtifactRepositoryManager artifactResolver;
    /**
     * Create an Artifact list builder.
     *
     */
    public ArtifactListBuilder(MavenArtifactRepositoryManager artifactResolver, Path localMvnRepoPath) {
        this.localMvnRepoPath = localMvnRepoPath;
        this.artifactResolver = artifactResolver;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }

    Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        MavenArtifact artifact = new MavenArtifact();
        artifact.setArtifactId(coords.getArtifactId());
        artifact.setGroupId(coords.getGroupId());
        artifact.setVersion(coords.getVersion());
        artifact.setClassifier(coords.getClassifier());
        artifact.setExtension(coords.getExtension());
        artifactResolver.resolve(artifact);
        return artifact.getPath();
    }

    private String checksum(String filepath) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(filepath))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bis.read(buffer)) > 0) {
                md.update(buffer, 0, count);
            }
        }

        byte[] hash = md.digest();
        return DatatypeConverter.printHexBinary(hash).toLowerCase();
    }

    public Path add(ArtifactCoords coords) throws ProvisioningException, ArtifactDescriptorException, IOException {
        Path artifactLocalPath = resolveArtifact(coords);
        ArtifactCoords pomFileCoords = new ArtifactCoords(coords.getGroupId(), coords.getArtifactId(), coords.getVersion(), null, "pom");
        Path pomFile = resolveArtifact(pomFileCoords);
        Model model = readModel(pomFile);
        Parent artifactParent = model.getParent();
        if (artifactParent != null) {
            ArtifactCoords parentCoords = new ArtifactCoords(artifactParent.getGroupId(), artifactParent.getArtifactId(), artifactParent.getVersion(), null, "pom");
            Path parentFile = resolveArtifact(parentCoords);
            addArtifact(parentFile);
        }
        addArtifact(artifactLocalPath);
        addArtifact(pomFile);
        return artifactLocalPath;
    }

    private static Model readModel(final Path pomXml) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(pomXml)) {
            final MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
            final Model model = xpp3Reader.read(reader);
            model.setPomFile(pomXml.toFile());
            return model;
        } catch (org.codehaus.plexus.util.xml.pull.XmlPullParserException ex) {
            throw new IOException("Failed to parse artifact POM model", ex);
        }
    }

    public String build() {
        StringBuilder builder = new StringBuilder();
        for (Entry<String, String> entry : map.entrySet()) {
            builder.append(entry.getValue()).append(",").append(entry.getKey()).append(System.lineSeparator());
        }

        return builder.toString();
    }

    protected Map<String, String> getMap() {
        return map;
    }

    private void addArtifact(Path artifactLocalPath) {
        Path relativized = localMvnRepoPath.relativize(artifactLocalPath);
        try {
            map.put(relativized.toString(), checksum(artifactLocalPath.toString()));
        } catch (IOException ex) {
            throw new RuntimeException("Can't add " + artifactLocalPath + " to zip file", ex);
        }
    }
}
