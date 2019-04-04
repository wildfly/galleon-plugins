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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Copy artifacts from local repository to a directory.
 *
 * @author jdenise@redhat.com
 */
public class MavenRepoBuilder {

    private static final String ROOT_PATH = "repository";
    private static final String POM = ".pom";
    private static final String MD5 = ".md5";
    private static final String SHA = ".sha";

    private final Path localMvnRepoPath;
    private final Path targetDirectory;
    /**
     * Create a maven repository builder.
     *
     * @param targetDirectory The directory in which maven repository is built.
     * @param localMvnRepoPath The path to local maven repository
     */
    public MavenRepoBuilder(Path targetDirectory, Path localMvnRepoPath) {
        this.localMvnRepoPath = localMvnRepoPath;
        this.targetDirectory = targetDirectory;
    }

    public void add(Path artifactLocalPath) {
        addArtifact(localMvnRepoPath, artifactLocalPath);
        try (Stream<Path> files = Files.list(artifactLocalPath.getParent())) {
            files.filter(MavenRepoBuilder::checkAddPath).forEach(new Consumer<Path>() {
                @Override
                public void accept(Path t) {
                    addArtifact(localMvnRepoPath, t);
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException("Can't retrieve files in " + artifactLocalPath.getParent(), ex);
        }
    }

    private void addArtifact(Path localMvnRepoPath, Path artifactLocalPath) {
        Path relativized = localMvnRepoPath.relativize(artifactLocalPath);
        Path pathInZipfile = Paths.get(targetDirectory.toString(), ROOT_PATH, relativized.toString());
        try {
            Files.createDirectories(pathInZipfile.getParent());
            Files.copy(artifactLocalPath, pathInZipfile,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("Can't add " + artifactLocalPath + " to zip file", ex);
        }
    }

    private static boolean checkAddPath(Path path) {
        String name = path.toString();
        return name.endsWith(POM)
                || name.endsWith(MD5)
                || name.endsWith(SHA);
    }

}
