/*
 * Copyright 2016-2026 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven.shaded;

import dev.cyberstamp.maven.assembly.sbom.ArtifactCoords;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Serializer;

/**
 * Writes a {@link ShadedArtifacts} model to an XML file using StAX.
 *
 * <p>Example output:
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <shaded-artifacts>
 *   <artifact>
 *     <groupId>com.example</groupId>
 *     <artifactId>uber-jar</artifactId>
 *     <version>1.0.0</version>
 *     <classifier>shaded</classifier>
 *     <type>jar</type>
 *     <shaded-dependencies>
 *       <dependency>
 *         <groupId>org.some</groupId>
 *         <artifactId>lib</artifactId>
 *         <version>2.3.1</version>
 *         <type>jar</type>
 *       </dependency>
 *     </shaded-dependencies>
 *   </artifact>
 * </shaded-artifacts>
 * }</pre>
 */
public final class ShadedArtifactsXmlWriter {

    private static final String ELEM_SHADED_ARTIFACTS = "shaded-artifacts";
    private static final String ELEM_ARTIFACT = "artifact";
    private static final String ELEM_GROUP_ID = "groupId";
    private static final String ELEM_ARTIFACT_ID = "artifactId";
    private static final String ELEM_VERSION = "version";
    private static final String ELEM_CLASSIFIER = "classifier";
    private static final String ELEM_TYPE = "type";
    private static final String ELEM_SHADED_DEPS = "shaded-dependencies";
    private static final String ELEM_DEPENDENCY = "dependency";

    private ShadedArtifactsXmlWriter() {
    }

    /**
     * Writes {@code model} to {@code outputPath}, creating parent directories as needed.
     */
    public static void write(List<FatJar> model, Path outputPath) throws IOException {
        Element root = new Element(ELEM_SHADED_ARTIFACTS);
        for (FatJar artifact : model) {
            root.appendChild(buildArtifact(artifact));
        }
        Files.createDirectories(outputPath.getParent());
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            Serializer serializer = new Serializer(out, "UTF-8");
            serializer.setIndent(2);
            serializer.write(new Document(root));
        }
    }

    private static Element buildArtifact(FatJar artifact) {
        Element elem = new Element(ELEM_ARTIFACT);
        appendCoords(elem, artifact.getCoords());
        Element deps = new Element(ELEM_SHADED_DEPS);
        for (ArtifactCoords dep : artifact.getShadedDependencies()) {
            Element depElem = new Element(ELEM_DEPENDENCY);
            appendCoords(depElem, dep);
            deps.appendChild(depElem);
        }
        elem.appendChild(deps);
        return elem;
    }

    private static void appendCoords(Element parent, ArtifactCoords coords) {
        appendChild(parent, ELEM_GROUP_ID, coords.groupId());
        appendChild(parent, ELEM_ARTIFACT_ID, coords.artifactId());
        appendChild(parent, ELEM_VERSION, coords.version());
        if (coords.classifier() != null && !coords.classifier().isEmpty()) {
            appendChild(parent, ELEM_CLASSIFIER, coords.classifier());
        }
        appendChild(parent, ELEM_TYPE, coords.type());
    }

    private static void appendChild(Element parent, String name, String value) {
        Element child = new Element(name);
        child.appendChild(value);
        parent.appendChild(child);
    }
}
