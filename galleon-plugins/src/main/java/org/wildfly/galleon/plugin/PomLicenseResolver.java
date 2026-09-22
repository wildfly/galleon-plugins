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
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import dev.cyberstamp.maven.assembly.sbom.LicenseSource;
import dev.cyberstamp.maven.assembly.sbom.RawLicense;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A {@link LicenseSource} that reads raw {@code <licenses>} declarations from
 * Maven POMs resolved via a {@link MavenRepoManager}, following the parent POM
 * chain until licenses are found.
 *
 * <p>SPDX mapping is performed downstream by assembly-sbom-core's shared
 * {@code SpdxLicenseMapper} (via {@code LicenseEnrichment}); this class does no
 * SPDX resolution itself. It returns neutral {@link RawLicense} name/URL pairs
 * exactly as declared in the POM.</p>
 */
class PomLicenseResolver implements LicenseSource {

    private static final int MAX_PARENT_DEPTH = 10;

    private final MavenRepoManager maven;
    private final DocumentBuilderFactory docBuilderFactory;
    private final Map<String, List<RawLicense>> cache = new ConcurrentHashMap<>();

    PomLicenseResolver(MavenRepoManager maven) {
        this.maven = maven;
        this.docBuilderFactory = DocumentBuilderFactory.newInstance();
        this.docBuilderFactory.setNamespaceAware(false);
    }

    /**
     * Returns the raw license declarations for the given artifact, walking the
     * parent POM chain until a {@code <licenses>} block is found.
     *
     * @return the declared licenses, or an empty list if none are found or the
     *         POM cannot be resolved
     */
    @Override
    public List<RawLicense> licensesFor(String groupId, String artifactId, String version) {
        return doResolve(groupId, artifactId, version, 0);
    }

    private List<RawLicense> doResolve(String groupId, String artifactId, String version, int depth) {
        if (depth >= MAX_PARENT_DEPTH) {
            return List.of();
        }
        final String key = groupId + ":" + artifactId + ":" + version;
        final List<RawLicense> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        final List<RawLicense> result = resolveUncached(groupId, artifactId, version, depth);
        cache.put(key, result);
        return result;
    }

    private List<RawLicense> resolveUncached(String groupId, String artifactId, String version, int depth) {
        final Document pom = resolvePom(groupId, artifactId, version);
        if (pom == null) {
            return List.of();
        }
        final List<RawLicense> licenses = parseLicenses(pom);
        if (!licenses.isEmpty()) {
            return licenses;
        }
        final String[] parent = parseParent(pom);
        if (parent == null) {
            return List.of();
        }
        return doResolve(parent[0], parent[1], parent[2], depth + 1);
    }

    private Document resolvePom(String groupId, String artifactId, String version) {
        final MavenArtifact pomArtifact = new MavenArtifact();
        pomArtifact.setGroupId(groupId);
        pomArtifact.setArtifactId(artifactId);
        pomArtifact.setVersion(version);
        pomArtifact.setExtension("pom");
        try {
            maven.resolve(pomArtifact);
            final Path pomPath = pomArtifact.getPath();
            if (pomPath == null || !Files.exists(pomPath)) {
                return null;
            }
            final DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
            try (InputStream is = Files.newInputStream(pomPath)) {
                return docBuilder.parse(is);
            }
        } catch (MavenUniverseException | IOException | SAXException
                | ParserConfigurationException e) {
            return null;
        }
    }

    private static List<RawLicense> parseLicenses(Document pom) {
        final NodeList top = pom.getDocumentElement().getChildNodes();
        Element licensesEl = null;
        for (int i = 0; i < top.getLength(); i++) {
            if (top.item(i) instanceof Element el && "licenses".equals(el.getTagName())) {
                licensesEl = el;
                break;
            }
        }
        if (licensesEl == null) {
            return List.of();
        }
        final NodeList licenseNodes = licensesEl.getElementsByTagName("license");
        final List<RawLicense> result = new ArrayList<>(licenseNodes.getLength());
        for (int i = 0; i < licenseNodes.getLength(); i++) {
            final Element el = (Element) licenseNodes.item(i);
            result.add(new RawLicense(childText(el, "name"), childText(el, "url")));
        }
        return result;
    }

    private static String[] parseParent(Document pom) {
        final NodeList children = pom.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && "parent".equals(el.getTagName())) {
                final String groupId = childText(el, "groupId");
                final String artifactId = childText(el, "artifactId");
                final String version = childText(el, "version");
                if (groupId != null && artifactId != null && version != null) {
                    return new String[]{groupId, artifactId, version};
                }
                return null;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        final String text = nodes.item(0).getTextContent();
        return text != null && !text.isBlank() ? text.trim() : null;
    }
}
