/*
 * Copyright 2016-2024 Red Hat, Inc. and/or its affiliates
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.ZipUtils;
import java.util.jar.Manifest;

/**
 * A shaded model.
 *
 * @author jdenise
 */
public class ShadedModel implements Utils.ArtifactResourceConsumer {

    public interface Installer {
        Path installCopiedArtifact(MavenArtifact a) throws IOException, ProvisioningException;
    }
    public static final String FILE_NAME = "shaded-model.xml";

    private final Map<String, Set<String>> classes = new HashMap<>();
    private final Map<String, List<String>> serviceLoaders = new HashMap<>();

    private final Element rootElement;
    private final Document document;
    private final Path tmpPath;
    private final WfInstallPlugin.ArtifactResolver artifactResolver;
    private final MessageWriter log;
    private final Map<String, String> mergedArtifactVersions;
    private final Optional<ArtifactRecorder> recorder;
    private boolean seenManifest;
    private final Installer installer;
    private final boolean channelArtifactResolution;
    private final boolean requireChannel;
    public ShadedModel(boolean requireChannel,
            Path shadedModel,
            Path tmpPath,
            WfInstallPlugin.ArtifactResolver artifactResolver,
            MessageWriter log, Map<String, String> mergedArtifactVersions,
            Installer installer,
            boolean channelArtifactResolution,
            Optional<ArtifactRecorder> recorder) throws IOException, ProvisioningDescriptionException {
        this.requireChannel = requireChannel;
        this.tmpPath = tmpPath;
        this.artifactResolver = artifactResolver;
        this.log = log;
        this.mergedArtifactVersions = mergedArtifactVersions;
        this.installer = installer;
        final Builder builder = new Builder(false);
        try (BufferedReader reader = Files.newBufferedReader(shadedModel, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        rootElement = document.getRootElement();
        this.channelArtifactResolution = channelArtifactResolution;
        this.recorder = recorder;
    }

    public List<MavenArtifact> getArtifacts() throws ProvisioningException, IOException {
        List<MavenArtifact> artifacts = new ArrayList<>();
        Element shadedDependencies = rootElement.getFirstChildElement("shaded-dependencies",
                rootElement.getNamespaceURI());
        Elements dependencies = shadedDependencies.getChildElements();
        for (int i = 0; i < dependencies.size(); i++) {
            Element e = dependencies.get(i);
            MavenArtifact a = Utils.toArtifactCoords(mergedArtifactVersions, e.getValue(), false, channelArtifactResolution, requireChannel);
            artifactResolver.resolve(a);
            if (log.isVerboseEnabled()) {
                log.verbose("Shadel model dependency: " + e.getValue() + " resolved version " + a.getVersion());
            }
            Path transformed = installer.installCopiedArtifact(a);
            a.setPath(transformed);
            artifacts.add(a);
            if (recorder.isPresent()) {
                recorder.get().cache(a, a.getPath());
            }
        }
        return artifacts;
    }

    public Map<String, String> getManifestEntries() {
        Map<String, String> entries = new HashMap<>();
        Element manifestEntries = rootElement.getFirstChildElement("manifestEntries",
                rootElement.getNamespaceURI());
        Elements elements = manifestEntries.getChildElements();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            entries.put(e.getLocalName(), e.getValue());
        }
        return entries;
    }

    public String getMainClass() {
        String ret = null;
        Element mainClass = rootElement.getFirstChildElement("main-class",
                rootElement.getNamespaceURI());
        if (mainClass != null) {
            ret = mainClass.getValue();
        }
        return ret;
    }

    public String getName() {
        return rootElement.getFirstChildElement("name",
                rootElement.getNamespaceURI()).getValue();
    }

    public void buildJar(Path shadedJar) throws IOException, ProvisioningException {
        if (log.isVerboseEnabled()) {
            log.verbose("Assembling shaded jar " + shadedJar);
        }
        Path tmpTarget = tmpPath.resolve("assemble_target").resolve(shadedJar.getFileName());
        Files.createDirectories(tmpTarget);
        for (MavenArtifact dependency : getArtifacts()) {
            Utils.navigateArtifact(dependency.getPath(), tmpTarget, this);
        }
        generateMetaInf(tmpTarget);
        ZipUtils.zip(tmpTarget, shadedJar);
        IoUtils.recursiveDelete(tmpTarget);
    }

    private void generateMetaInf(Path target) throws IOException {
        Path targetMetaInf = target.resolve("META-INF");
        Path targetManifestPath = targetMetaInf.resolve("MANIFEST.MF");
        Manifest manifest;
        if (!Files.exists(targetManifestPath)) {
            manifest = new Manifest();
        } else {
            try (FileInputStream stream = new FileInputStream(targetManifestPath.toFile())) {
                manifest = new Manifest(stream);
            }
        }
        Attributes attributes = manifest.getMainAttributes();
        String mainClass = getMainClass();
        if (mainClass != null) {
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        Map<String, String> manifestEntries = getManifestEntries();
        if (manifestEntries != null) {
            for (Map.Entry<String, String> entry : manifestEntries.entrySet()) {
                if (entry.getValue() == null) {
                    attributes.remove(new Attributes.Name(entry.getKey()));
                } else {
                    attributes.put(new Attributes.Name(entry.getKey()), entry.getValue());
                }
            }
        }
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "Galleon shading of " + getName());
        attributes.put(Attributes.Name.SPECIFICATION_TITLE, "Galleon shading of " + getName());
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "Unknown");
        Files.deleteIfExists(targetManifestPath);
        if (!Files.exists(targetMetaInf)) {
            Files.createDirectories(targetMetaInf);
        }
        try (FileOutputStream out = new FileOutputStream(targetManifestPath.toFile())) {
            manifest.write(out);
        }
        Path moduleInfoPath = target.resolve("module-info.class");
        Files.deleteIfExists(moduleInfoPath);
        Path indexListPath = target.resolve("META-INF/INDEX.LIST");
        Files.deleteIfExists(indexListPath);
        Path services = target.resolve("META-INF").resolve("services");
        for (Map.Entry<String, List<String>> entry : serviceLoaders.entrySet()) {
            Path file = services.resolve(entry.getKey());
            Files.write(file, entry.getValue(), UTF_8);
        }
    }

    @Override
    public boolean consume(Path resourcePath) throws IOException {
        String entry = resourcePath.toString().substring(1);
        if (entry.startsWith("META-INF/MANIFEST.MF")) {
            if (!seenManifest) {
                seenManifest = true;
                return true;
            } else {
                // Do not copy other Manifest files
                return false;
            }
        } else if (entry.startsWith("META-INF/services/")) {
            String fileName = resourcePath.getFileName().toString();
            List<String> lines = Files.readAllLines(resourcePath);
            List<String> allLines = serviceLoaders.get(fileName);
            Set<String> allClasses = classes.get(fileName);
            if (allLines == null) {
                allLines = new ArrayList<>();
                serviceLoaders.put(fileName, allLines);
            }
            if (allClasses == null) {
                allClasses = new HashSet<>();
                classes.put(fileName, allClasses);
            }
            boolean newClasses = false;
            for (String l : lines) {
                l = l.trim();
                if (l.isEmpty()) {
                    continue;
                }
                if (!l.startsWith("#")) {
                    if (!allClasses.contains(l)) {
                        newClasses = true;
                        break;
                    }
                }
            }
            if (newClasses) {
                for (String l : lines) {
                    l = l.trim();
                    if (l.isEmpty()) {
                        continue;
                    }
                    if (l.startsWith("#")) {
                        allLines.add(l);
                    } else {
                        if (allClasses.contains(l)) {
                            // Ignore the class.
                            continue;
                        }
                        allClasses.add(l);
                        allLines.add(l);
                    }
                }
            }
            return false;
        }
        return true;//cp.includeFile(entry);
    }
}
