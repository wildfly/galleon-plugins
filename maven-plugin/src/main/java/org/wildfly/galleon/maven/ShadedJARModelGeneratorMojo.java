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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.jboss.galleon.spec.PackageSpec;
import org.jboss.galleon.xml.PackageXmlWriter;

@Mojo(name = "generate-shaded-descriptor", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class ShadedJARModelGeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    String projectBuildDir;

    @Parameter(alias = "main-class")
    String mainClass;
    @Parameter()
    Map<String, String> manifestEntries;
    @Component
    private MavenProjectHelper projectHelper;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<Artifact> artifacts = new ArrayList<>();
        if (!"pom".equals(project.getArtifact().getType())) {
            Path classes = Paths.get(project.getBuild().getOutputDirectory());
            if (Files.exists(classes)) {
                artifacts.add(project.getArtifact());
            }
        }

        for (Artifact artifact : project.getArtifacts()) {
            if (MavenProjectArtifactVersions.TEST_JAR.equals(artifact.getType())
                    || MavenProjectArtifactVersions.SYSTEM.equals(artifact.getScope())) {
                continue;
            }
            artifacts.add(artifact);
        }
        try {
            String pkgName = project.getGroupId()+"."+project.getArtifactId()+".shaded";
            Path pkg = Paths.get(projectBuildDir).resolve("resources").resolve("packages").
                    resolve(pkgName);
            Files.createDirectories(pkg);
            Path pkgContent = pkg.resolve("pm").resolve("wildfly").resolve("shaded");
            Files.createDirectories(pkgContent);
            Path xmlFile = pkgContent.resolve("shaded-model.xml");
            Files.write(xmlFile, getXMLContent(project.getName(), artifacts, mainClass, manifestEntries).getBytes());
            PackageSpec spec = PackageSpec.builder().setName(pkgName).build();
            Path pkgFile = pkg.resolve("package.xml");
            PackageXmlWriter.getInstance().write(spec, pkgFile);
        } catch (XMLStreamException | IOException ex) {
            throw new MojoExecutionException(ex.getLocalizedMessage(), ex);
        }
    }

    public static String getXMLContent(String name, List<Artifact> artifacts, String mainClass, Map<String, String> manifestEntries) {
        StringBuilder builder = new StringBuilder();
        builder.append("<shaded-model>").append(System.lineSeparator());
        builder.append("<name>").append(name).append("</name>").append(System.lineSeparator());
        builder.append("<shaded-dependencies>").append(System.lineSeparator());
        for (Artifact a : artifacts) {
            builder.append(getDependency(a)).append(System.lineSeparator());
        }
        builder.append("</shaded-dependencies>").append(System.lineSeparator());
        if (mainClass != null) {
            builder.append("<main-class>");
            builder.append(mainClass);
            builder.append("</main-class>").append(System.lineSeparator());
        }
        if (manifestEntries != null) {
            builder.append("<manifestEntries>").append(System.lineSeparator());
            for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
                builder.append("<" + entry.getKey() + ">");
                builder.append(entry.getValue());
                builder.append("</" + entry.getKey() + ">").append(System.lineSeparator());
            }
            builder.append("</manifestEntries>").append(System.lineSeparator());
        }
        builder.append("</shaded-model>").append(System.lineSeparator());
        return builder.toString();
    }

    private static String getDependency(Artifact a) {
        StringBuilder builder = new StringBuilder();
        builder.append("<dependency>");
        builder.append(a.getGroupId()).append(":");
        builder.append(a.getArtifactId()).append(":").append(":");
        if (a.getClassifier() != null && !a.getClassifier().isEmpty()) {
            builder.append(a.getClassifier());
        }
        builder.append(":");
        builder.append(a.getType());
        builder.append("</dependency>").append(System.lineSeparator());
        return builder.toString();
    }
}
