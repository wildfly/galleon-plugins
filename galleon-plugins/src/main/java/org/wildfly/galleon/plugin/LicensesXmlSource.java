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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import dev.cyberstamp.maven.assembly.sbom.LicenseSource;
import dev.cyberstamp.maven.assembly.sbom.RawLicense;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A {@link LicenseSource} backed by the {@code *-licenses.xml} files that
 * WildFly/EAP feature packs ship under {@code docs/licenses}. These files use
 * the {@code license-maven-plugin} {@code licenseSummary} format, mapping a
 * Maven {@code groupId}/{@code artifactId}/{@code version} to its declared
 * license names and URLs.
 *
 * <p>Unlike {@link PomLicenseResolver}, this source needs no remote artifact
 * resolution: the data is already present in the provisioned distribution (and
 * in the feature-pack package content, so it is available in SBOM-only mode
 * too).</p>
 *
 * <p>Lookups are keyed by {@code groupId:artifactId} and are version-tolerant:
 * the version recorded in the summary may still contain unresolved
 * {@code ${...}} placeholders in raw package content, and a library's license
 * rarely varies across patch releases. SPDX mapping is performed downstream by
 * assembly-sbom-core's shared {@code SpdxLicenseMapper}; this class returns
 * neutral {@link RawLicense} name/URL pairs exactly as declared.</p>
 */
class LicensesXmlSource implements LicenseSource {

    private final Map<String, List<RawLicense>> byGa;

    private LicensesXmlSource(Map<String, List<RawLicense>> byGa) {
        this.byGa = byGa;
    }

    /**
     * Builds a source from a set of {@code licenseSummary} XML files. Files that
     * cannot be read or parsed are skipped (best-effort). When more than one
     * file declares the same {@code groupId:artifactId}, the first one wins.
     *
     * @param files the {@code *-licenses.xml} files to read; never {@code null}
     * @return a source over the merged mappings
     */
    static LicensesXmlSource from(List<Path> files) {
        final Map<String, List<RawLicense>> map = new HashMap<>();
        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        for (Path file : files) {
            parse(file, dbf, map);
        }
        return new LicensesXmlSource(map);
    }

    @Override
    public List<RawLicense> licensesFor(String groupId, String artifactId, String version) {
        return byGa.getOrDefault(groupId + ":" + artifactId, List.of());
    }

    private static void parse(Path file, DocumentBuilderFactory dbf,
            Map<String, List<RawLicense>> map) {
        try (InputStream is = Files.newInputStream(file)) {
            final DocumentBuilder builder = dbf.newDocumentBuilder();
            final Document doc = builder.parse(is);
            final NodeList dependencies = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                if (!(dependencies.item(i) instanceof Element dependency)) {
                    continue;
                }
                final String groupId = childText(dependency, "groupId");
                final String artifactId = childText(dependency, "artifactId");
                if (groupId == null || artifactId == null) {
                    continue;
                }
                final List<RawLicense> licenses = parseLicenses(dependency);
                if (!licenses.isEmpty()) {
                    map.putIfAbsent(groupId + ":" + artifactId, licenses);
                }
            }
        } catch (Exception e) {
            // best-effort: a malformed summary must not fail SBOM generation
        }
    }

    private static List<RawLicense> parseLicenses(Element dependency) {
        final NodeList licenseNodes = dependency.getElementsByTagName("license");
        final List<RawLicense> result = new ArrayList<>(licenseNodes.getLength());
        for (int i = 0; i < licenseNodes.getLength(); i++) {
            final Element license = (Element) licenseNodes.item(i);
            result.add(new RawLicense(childText(license, "name"), childText(license, "url")));
        }
        return result;
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
