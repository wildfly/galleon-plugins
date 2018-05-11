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
package org.wildfly.galleon.maven;

import com.google.common.base.Charsets;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.logging.Log;
import org.jboss.galleon.ArtifactCoords;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ModuleXmlVersionResolver {

    private static final String MODULES = "modules";

    public static void filterAndConvertModules(Path fpDirectory, Path targetModuleDir, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException {
        Files.walkFileTree(fpDirectory, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isModules(dir)) {
                    debug(log, "Copying %s to %s", dir, targetModuleDir);
                    convertModules(dir, targetModuleDir, artifacts, hardcodedArtifacts, log);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            private void debug(Log log, String format, Object... args) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format(format, args));
                }
            }
        });
    }

    public static void convertModules(Path source, Path target, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
        } else {
            Files.createDirectories(target.getParent());
        }
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                final Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.copy(dir, targetDir);
                } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(targetDir)) {
                        throw e;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                try {
                    if ("module.xml".equals(file.getFileName().toString())) {
                        convertModule(file, target.resolve(source.relativize(file)), artifacts, hardcodedArtifacts, log);
                    } else {
                        Files.copy(file, target.resolve(source.relativize(file)));
                    }
                } catch (XMLStreamException ex) {
                    log.error("Error reading " + file, ex);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isModules(Path dir) {
        return MODULES.equals(dir.getFileName().toString());
    }

    public static void convertModule(final Path file, Path target, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException, XMLStreamException {
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        Files.deleteIfExists(target);
        try (Reader is = Files.newBufferedReader(file, Charsets.UTF_8);
                Writer out = Files.newBufferedWriter(target, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            convert(inputFactory.createXMLEventReader(is), outputFactory.createXMLEventWriter(out), artifacts, hardcodedArtifacts, log);
        }
    }

    private static void convert(final XMLEventReader r, final XMLEventWriter w, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) throws IOException, XMLStreamException {
        XMLEventFactory eventFactory = XMLEventFactory.newInstance();
        while (r.hasNext()) {
            XMLEvent event = r.nextEvent();
            switch (event.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    StartElement startElement = event.asStartElement();
                    if ("module".equals(startElement.getName().getLocalPart())) {
                        StartElement convertedModule = convertModuleElement(eventFactory, startElement, artifacts);
                        log.debug(startElement + " has been converted to " + convertedModule);
                        w.add(convertedModule);
                    } else if ("artifact".equals(startElement.getName().getLocalPart())) {
                        StartElement convertedArtifact = convertArtifactElement(eventFactory, startElement, artifacts, hardcodedArtifacts, log);
                        log.debug(startElement + " has been converted to " + convertedArtifact);
                        w.add(convertedArtifact);
                    } else {
                        w.add(event);
                    }
                    break;
                case XMLStreamConstants.START_DOCUMENT:
                case XMLStreamConstants.END_DOCUMENT:
                case XMLStreamConstants.PROCESSING_INSTRUCTION:
                case XMLStreamConstants.END_ELEMENT:
                case XMLStreamConstants.COMMENT:
                case XMLStreamConstants.CDATA:
                case XMLStreamConstants.SPACE:
                case XMLStreamConstants.CHARACTERS:
                case XMLStreamConstants.NAMESPACE:
                    w.add(event);
                    break;
            }
        }
        w.flush();
        w.close();
    }

    private static StartElement convertArtifactElement(XMLEventFactory eventFactory, StartElement artifactElement, Map<String, Artifact> artifacts, List<Artifact> hardcodedArtifacts, Log log) {
        List<Attribute> attributes = new ArrayList<>();
        Iterator<?> iter = artifactElement.getAttributes();
        while (iter.hasNext()) {
            Attribute attribute = (Attribute) iter.next();
            if ("name".equals(attribute.getName().getLocalPart())) {
                String artifactName = attribute.getValue();
                String artifactCoords = getArtifactCoordinates(artifactName);
                if (artifactCoords != null) {
                    Artifact artifact = artifacts.get(artifactCoords);
                    if (artifact == null) {
                        log.warn("Couldn't locate artifact in the dependencies " + artifactCoords);
                        attributes.add(attribute);
                    } else {
                        StringJoiner joiner = new StringJoiner(":");
                        joiner.add(artifact.getGroupId());
                        joiner.add(artifact.getArtifactId());
                        joiner.add(artifact.getVersion());
                        if (artifact.hasClassifier()) {
                            joiner.add(artifact.getClassifier());
                        }
                        attributes.add(eventFactory.createAttribute(attribute.getName(), joiner.toString()));
                    }
                } else {
                    attributes.add(attribute);
                    final ArtifactCoords coords = ArtifactCoords.fromString(artifactName);
                    hardcodedArtifacts.add(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getVersion(),
                            "provided", coords.getExtension(), coords.getClassifier(), new DefaultArtifactHandler(coords.getExtension())));
                }
            } else {
                attributes.add(attribute);
            }
        }
        return eventFactory.createStartElement(artifactElement.getName(), attributes.iterator(), artifactElement.getNamespaces());
    }

    private static StartElement convertModuleElement(XMLEventFactory eventFactory, StartElement module, Map<String, Artifact> artifacts) {
        List<Attribute> attributes = new ArrayList<>();
        Iterator<?> iter = module.getAttributes();
        while (iter.hasNext()) {
            Attribute attribute = (Attribute) iter.next();
            if ("version".equals(attribute.getName().getLocalPart())) {
                String artifactName = attribute.getValue();
                String artifactCoords = getArtifactCoordinates(artifactName);
                if (artifactCoords != null) {
                    attributes.add(eventFactory.createAttribute("version", artifacts.get(artifactCoords).getVersion()));
                } else {
                    attributes.add(attribute);
                }
            } else {
                attributes.add(attribute);
            }
        }
        return eventFactory.createStartElement(module.getName(), attributes.iterator(), module.getNamespaces());
    }

    private static String getArtifactCoordinates(String artifactName) {
        String artifactCoords = null;
        if (artifactName.startsWith("${") && artifactName.endsWith("}")) {
            String ct = artifactName.substring(2, artifactName.length() - 1);
            if (ct.contains("?")) {
                String[] split = ct.split("\\?");
                artifactCoords = split[0];
            } else {
                artifactCoords = ct;
            }
        }
        return artifactCoords;
    }
}
