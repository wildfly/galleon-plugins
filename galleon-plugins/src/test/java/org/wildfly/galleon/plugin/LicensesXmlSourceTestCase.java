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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.cyberstamp.maven.assembly.sbom.RawLicense;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LicensesXmlSourceTestCase {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resolvesLicensesByGroupAndArtifact() throws Exception {
        final Path xml = writeSummary("a-licenses.xml",
                dependency("com.graphql-java", "graphql-java", "24.3",
                        license("MIT", "https://example.org/mit")));

        final LicensesXmlSource source = LicensesXmlSource.from(List.of(xml));
        final List<RawLicense> licenses = source.licensesFor("com.graphql-java", "graphql-java", "24.3");

        assertEquals(1, licenses.size());
        assertEquals("MIT", licenses.get(0).name());
        assertEquals("https://example.org/mit", licenses.get(0).url());
    }

    @Test
    public void ignoresVersionWhenMatching() throws Exception {
        final Path xml = writeSummary("a-licenses.xml",
                dependency("io.smallrye", "smallrye-graphql", "${version.io.smallrye}",
                        license("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")));

        final LicensesXmlSource source = LicensesXmlSource.from(List.of(xml));
        // a different (resolved) version still matches, since keying is by groupId:artifactId
        final List<RawLicense> licenses = source.licensesFor("io.smallrye", "smallrye-graphql", "2.16.0");

        assertEquals(1, licenses.size());
        assertEquals("Apache License, Version 2.0", licenses.get(0).name());
    }

    @Test
    public void mergesMultipleFilesFirstWins() throws Exception {
        final Path a = writeSummary("a-licenses.xml",
                dependency("org.test", "lib", "1.0", license("Apache-2.0", "https://a")));
        final Path b = writeSummary("b-licenses.xml",
                dependency("org.test", "lib", "1.0", license("MIT", "https://b")));

        final LicensesXmlSource source = LicensesXmlSource.from(List.of(a, b));
        final List<RawLicense> licenses = source.licensesFor("org.test", "lib", "1.0");

        assertEquals(1, licenses.size());
        assertEquals("Apache-2.0", licenses.get(0).name());
    }

    @Test
    public void unknownArtifactReturnsEmpty() throws Exception {
        final Path xml = writeSummary("a-licenses.xml",
                dependency("org.test", "lib", "1.0", license("Apache-2.0", "https://a")));

        final LicensesXmlSource source = LicensesXmlSource.from(List.of(xml));
        assertTrue(source.licensesFor("org.other", "missing", "9.9").isEmpty());
    }

    @Test
    public void malformedFileIsSkipped() throws Exception {
        final Path bad = temp.newFile("bad-licenses.xml").toPath();
        Files.writeString(bad, "<licenseSummary><dependencies><dependency>");
        final Path good = writeSummary("good-licenses.xml",
                dependency("org.test", "lib", "1.0", license("Apache-2.0", "https://a")));

        final LicensesXmlSource source = LicensesXmlSource.from(List.of(bad, good));
        assertEquals(1, source.licensesFor("org.test", "lib", "1.0").size());
    }

    private Path writeSummary(String name, String... dependencies) throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        sb.append("<licenseSummary>\n  <dependencies>\n");
        for (String dep : dependencies) {
            sb.append(dep);
        }
        sb.append("  </dependencies>\n</licenseSummary>\n");
        final Path file = temp.newFile(name).toPath();
        Files.writeString(file, sb.toString());
        return file;
    }

    private static String dependency(String groupId, String artifactId, String version, String... licenses) {
        final StringBuilder sb = new StringBuilder();
        sb.append("    <dependency>\n");
        sb.append("      <groupId>").append(groupId).append("</groupId>\n");
        sb.append("      <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("      <version>").append(version).append("</version>\n");
        sb.append("      <licenses>\n");
        for (String license : licenses) {
            sb.append(license);
        }
        sb.append("      </licenses>\n");
        sb.append("    </dependency>\n");
        return sb.toString();
    }

    private static String license(String name, String url) {
        return "        <license>\n"
                + "          <name>" + name + "</name>\n"
                + "          <url>" + url + "</url>\n"
                + "          <distribution>repo</distribution>\n"
                + "        </license>\n";
    }
}
