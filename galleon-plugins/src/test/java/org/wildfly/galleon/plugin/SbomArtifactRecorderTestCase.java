package org.wildfly.galleon.plugin;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import dev.cyberstamp.maven.assembly.sbom.SchemaVersions;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.cyclonedx.parsers.JsonParser;
import org.cyclonedx.parsers.XmlParser;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SbomArtifactRecorderTestCase {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private Path installBase;

    @Before
    public void setUp() throws Exception {
        installBase = temp.newFolder("server-root").toPath();
    }

    @Test
    public void generateJsonSbomWithSingleArtifact() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact artifact = mavenArtifact("org.wildfly.core", "wildfly-launcher", "31.0.0.Final");
        final Path target = createArtifactFile("modules/launcher/wildfly-launcher-31.0.0.Final.jar");

        recorder.record(artifact, target);
        recorder.writeManifest();

        assertTrue("SBOM file should exist", Files.exists(outputFile));
        final Bom bom = new JsonParser().parse(outputFile.toFile());

        assertNotNull("BOM should have components", bom.getComponents());
        final Component component = findComponent(bom, "wildfly-launcher");
        assertNotNull(component);
        assertEquals(Component.Type.LIBRARY, component.getType());
        assertEquals("org.wildfly.core", component.getGroup());
        assertEquals("31.0.0.Final", component.getVersion());
        assertTrue(component.getPurl().contains("pkg:maven/org.wildfly.core/wildfly-launcher@31.0.0.Final"));
    }

    @Test
    public void flatArtifactHasNoEmptyLeafDependencyEntry() throws Exception {
        // Provisioning records a flat inventory, not a resolved dependency graph,
        // so a recorded artifact must not be declared as an empty dependency entry
        // (which would falsely assert it has no dependencies).
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact artifact = mavenArtifact("org.wildfly.core", "wildfly-launcher", "31.0.0.Final");
        recorder.record(artifact, createArtifactFile("modules/launcher/wildfly-launcher-31.0.0.Final.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final String artifactRef = "pkg:maven/org.wildfly.core/wildfly-launcher@31.0.0.Final";
        assertTrue("flat artifact must not have its own dependency entry",
                bom.getDependencies().stream().noneMatch(d -> artifactRef.equals(d.getRef())));
    }

    @Test
    public void generateXmlSbom() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.xml");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "xml");

        recorder.record(mavenArtifact("org.test", "test-lib", "1.0.0"), createArtifactFile("lib/test-lib-1.0.0.jar"));
        recorder.writeManifest();

        assertTrue("SBOM file should exist", Files.exists(outputFile));
        final Bom bom = new XmlParser().parse(outputFile.toFile());
        assertNotNull(findComponent(bom, "test-lib"));
    }

    @Test
    public void evidenceContainsRelativePath() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final Path target = createArtifactFile("modules/system/layers/base/org/test/main/test-1.0.jar");
        recorder.record(mavenArtifact("org.test", "test", "1.0"), target);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component component = findComponent(bom, "test");
        assertNotNull("Component should have evidence", component.getEvidence());
        assertNotNull("Evidence should have occurrences", component.getEvidence().getOccurrences());

        final List<Occurrence> occurrences = component.getEvidence().getOccurrences();
        assertTrue(occurrences.stream().anyMatch(
                o -> "modules/system/layers/base/org/test/main/test-1.0.jar".equals(o.getLocation())));
    }

    @Test
    public void multipleOccurrencesForSameArtifact() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact artifact = mavenArtifact("org.test", "test-lib", "1.0.0");
        recorder.record(artifact, createArtifactFile("modules/a/test-lib-1.0.0.jar"));
        recorder.record(artifact, createArtifactFile("modules/b/test-lib-1.0.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component component = findComponent(bom, "test-lib");
        assertNotNull(component);
        assertTrue("Same artifact recorded twice should have 2 occurrences",
                component.getEvidence().getOccurrences().size() >= 2);
    }

    @Test
    public void multipleDistinctArtifacts() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.test", "lib-a", "1.0"), createArtifactFile("lib/lib-a-1.0.jar"));
        recorder.record(mavenArtifact("org.test", "lib-b", "2.0"), createArtifactFile("lib/lib-b-2.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull(findComponent(bom, "lib-a"));
        assertNotNull(findComponent(bom, "lib-b"));
    }

    @Test
    public void purlIncludesClassifierAndType() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId("org.test");
        artifact.setArtifactId("test-lib");
        artifact.setVersion("1.0.0");
        artifact.setClassifier("linux-x86_64");
        artifact.setExtension("so");

        recorder.record(artifact, createArtifactFile("lib/test-lib-1.0.0-linux-x86_64.so"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final String purl = findComponent(bom, "test-lib").getPurl();
        assertTrue("PURL should contain classifier", purl.contains("classifier=linux-x86_64"));
        assertTrue("PURL should contain type", purl.contains("type=so"));
    }

    @Test
    public void cacheRecordsArtifact() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact artifact = mavenArtifact("org.test", "cached-lib", "1.0.0");
        final Path jarSrc = createArtifactFile("external/cached-lib-1.0.0.jar");
        recorder.cache(artifact, jarSrc);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component component = findComponent(bom, "cached-lib");
        assertNotNull(component);
        // cache() should record the hash from the source JAR but produce no occurrence
        assertNotNull("cache() should produce a hash", component.getHashes());
        assertFalse("cache() should produce a hash", component.getHashes().isEmpty());
        assertTrue("cache() should produce no occurrence",
                component.getEvidence() == null
                || component.getEvidence().getOccurrences() == null
                || component.getEvidence().getOccurrences().isEmpty());
    }

    @Test
    public void bomHasMetadata() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("BOM should have metadata", bom.getMetadata());
        assertNotNull("Metadata should have timestamp", bom.getMetadata().getTimestamp());
    }

    @Test
    public void mainComponentDerivedFromProductConf() throws Exception {
        // The distribution's branding lives in the recorded product-conf module
        // artifact (artifactId ending -product-conf); its manifest carries the
        // release name/version/vendor. This is present in both a full provision
        // and SBOM-only mode, since it is recorded as a module dependency.
        final Path confJar = createProductConfJar(
                "modules/system/layers/base/org/jboss/as/product/main/wildfly-feature-pack-product-conf-40.0.0.Final.jar",
                "WildFly", "40.0.0.Final", "WildFly", "40.0.0.Final-01");
        final MavenArtifact confArtifact =
                mavenArtifact("org.wildfly", "wildfly-feature-pack-product-conf", "40.0.0.Final");
        confArtifact.setExtension("jar");
        confArtifact.setPath(confJar);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(confArtifact, confJar);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertNotNull(main);
        assertEquals("WildFly", main.getName());
        assertEquals("40.0.0.Final", main.getVersion());
        assertEquals("pkg:generic/wildfly@40.0.0.final", main.getPurl());
        assertEquals("pkg:generic/wildfly@40.0.0.final", main.getBomRef());
        assertEquals("WildFly", main.getPublisher());
        // build-version is no longer emitted, even though the manifest carries JBossAS-Release-Version
        assertTrue("no build-version property should be emitted",
                main.getProperties() == null
                        || main.getProperties().stream().noneMatch(p -> "build-version".equals(p.getName())));
        // the dependency-graph root must reference the synthetic bom-ref
        assertTrue("dependency root should use the synthetic product bom-ref",
                bom.getDependencies().stream()
                        .anyMatch(d -> "pkg:generic/wildfly@40.0.0.final".equals(d.getRef())));
    }

    @Test
    public void mainComponentIncludesCpeFromProductConf() throws Exception {
        final String cpe = "cpe:2.3:a:wildfly:wildfly:40.0.0:*:*:*:*:*:*:*";
        final Path confJar = createProductConfJar(
                "modules/system/layers/base/org/jboss/as/product/main/wildfly-feature-pack-product-conf-40.0.0.Final.jar",
                "WildFly", "40.0.0.Final", "WildFly", "40.0.0.Final-01", cpe);
        final MavenArtifact confArtifact =
                mavenArtifact("org.wildfly", "wildfly-feature-pack-product-conf", "40.0.0.Final");
        confArtifact.setExtension("jar");
        confArtifact.setPath(confJar);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(confArtifact, confJar);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertNotNull(main);
        assertEquals(cpe, main.getCpe());
    }

    @Test
    public void mainComponentHasNoCpeWhenProductConfOmitsIt() throws Exception {
        final Path confJar = createProductConfJar(
                "modules/system/layers/base/org/jboss/as/product/main/wildfly-feature-pack-product-conf-40.jar",
                "WildFly", "40.0.0.Final", "WildFly", "40.0.0.Final");
        final MavenArtifact confArtifact =
                mavenArtifact("org.wildfly", "wildfly-feature-pack-product-conf", "40.0.0.Final");
        confArtifact.setExtension("jar");
        confArtifact.setPath(confJar);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(confArtifact, confJar);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertNotNull(main);
        assertEquals("WildFly", main.getName());
        assertNull("no CPE should be emitted when the product-conf omits it", main.getCpe());
    }

    @Test
    public void mainComponentFallsBackToGenericWithoutProductConf() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertNotNull(main);
        assertEquals("wildfly", main.getName());
        assertEquals("pkg:generic/wildfly", main.getPurl());
    }

    @Test
    public void toolIsWildflyGalleonPlugins() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("BOM should have tools", bom.getMetadata().getToolChoice());
        assertNotNull("tools should have components", bom.getMetadata().getToolChoice().getComponents());
        assertTrue("the SBOM-generating tool should be wildfly-galleon-plugins",
                bom.getMetadata().getToolChoice().getComponents().stream()
                        .anyMatch(c -> "wildfly-galleon-plugins".equals(c.getName())
                                && "org.wildfly.galleon-plugins".equals(c.getGroup())));
    }

    @Test
    public void recordWithNullTargetCreatesComponentWithoutOccurrences() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.test", "no-location-lib", "2.0.0"), null);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component component = findComponent(bom, "no-location-lib");
        assertNotNull(component);
        assertEquals("org.test", component.getGroup());
        assertEquals("2.0.0", component.getVersion());
        assertTrue(component.getEvidence() == null
                || component.getEvidence().getOccurrences() == null
                || component.getEvidence().getOccurrences().isEmpty());
    }

    @Test
    public void externalPathProducesNoOccurrences() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final Path externalPath = temp.newFolder("maven-repo").toPath().resolve("org/test/ext-lib-1.0.jar");
        Files.createDirectories(externalPath.getParent());
        Files.writeString(externalPath, "external");

        recorder.record(mavenArtifact("org.test", "ext-lib", "1.0"), externalPath);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component component = findComponent(bom, "ext-lib");
        assertNotNull(component);
        assertTrue(component.getEvidence() == null
                || component.getEvidence().getOccurrences() == null
                || component.getEvidence().getOccurrences().isEmpty());
    }

    @Test
    public void thinServerArtifactRecordedWithNullTarget() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.wildfly.core", "wildfly-server", "31.0.0.Final"), null);
        recorder.record(mavenArtifact("org.wildfly.core", "wildfly-launcher", "31.0.0.Final"),
                createArtifactFile("modules/launcher/wildfly-launcher-31.0.0.Final.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());

        final Component thinArtifact = findComponent(bom, "wildfly-server");
        assertNotNull(thinArtifact);
        assertTrue("Thin artifact should have no occurrences",
                thinArtifact.getEvidence() == null
                || thinArtifact.getEvidence().getOccurrences() == null
                || thinArtifact.getEvidence().getOccurrences().isEmpty());

        final Component fatArtifact = findComponent(bom, "wildfly-launcher");
        assertNotNull(fatArtifact);
        assertNotNull("Fat artifact should have evidence", fatArtifact.getEvidence());
        assertFalse(fatArtifact.getEvidence().getOccurrences().isEmpty());
    }

    @Test
    public void shadedComponentWithNestedDependencies() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final List<MavenArtifact> deps = List.of(
                mavenArtifact("org.jboss", "dep-a", "1.0"),
                mavenArtifact("org.jboss", "dep-b", "2.0"));
        final Path target = createArtifactFile("bin/client/jboss-cli-client.jar");
        recorder.recordShadedComponent("bin/client/jboss-cli-client.jar", "31.0.0.Final", target, deps);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component shaded = findComponent(bom, "jboss-cli-client");
        assertNotNull(shaded);
        assertEquals("31.0.0.Final", shaded.getVersion());
        assertEquals("pkg:generic/jboss-cli-client@31.0.0.Final", shaded.getPurl());

        assertNotNull("Shaded JAR should have evidence", shaded.getEvidence());
        assertTrue(shaded.getEvidence().getOccurrences().stream()
                .anyMatch(o -> "bin/client/jboss-cli-client.jar".equals(o.getLocation())));

        assertNotNull("Shaded JAR should have nested components", shaded.getComponents());
        assertEquals(2, shaded.getComponents().size());
    }

    @Test
    public void shadedComponentWithNullTargetHasNoEvidence() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final List<MavenArtifact> deps = List.of(mavenArtifact("org.jboss", "dep-a", "1.0"));
        recorder.recordShadedComponent("lib/shaded-lib.jar", "1.0.0", null, deps);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component shaded = findComponent(bom, "shaded-lib");
        assertNotNull(shaded);
        assertTrue(shaded.getEvidence() == null
                || shaded.getEvidence().getOccurrences() == null
                || shaded.getEvidence().getOccurrences().isEmpty());
        assertEquals(1, shaded.getComponents().size());
    }

    @Test
    public void shadedOnlyArtifactExcludedFromTopLevel() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact shadedOnlyDep = mavenArtifact("org.jboss", "shaded-only", "1.0");
        final MavenArtifact independentLib = mavenArtifact("org.wildfly", "standalone-lib", "2.0");

        recorder.cache(shadedOnlyDep, Path.of("/tmp/external/shaded-only-1.0.jar"));
        recorder.record(independentLib, createArtifactFile("modules/standalone-lib-2.0.jar"));
        recorder.recordShadedComponent("bin/uber.jar", "1.0.0", createArtifactFile("bin/uber.jar"),
                List.of(shadedOnlyDep));

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull(findComponent(bom, "standalone-lib"));
        assertNotNull(findComponent(bom, "uber"));
        assertTrue("shaded-only should NOT be top-level",
                bom.getComponents().stream().noneMatch(c -> "shaded-only".equals(c.getName())));
    }

    @Test
    public void artifactUsedInBothShadedAndDistributionStaysTopLevel() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact sharedDep = mavenArtifact("org.jboss.logging", "jboss-logging", "3.6.0");

        recorder.record(sharedDep, createArtifactFile("modules/jboss-logging-3.6.0.jar"));
        recorder.recordShadedComponent("bin/cli.jar", "1.0.0", createArtifactFile("bin/cli.jar"),
                List.of(sharedDep));

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("jboss-logging should be top-level (has distribution location)",
                findComponent(bom, "jboss-logging"));
        assertNotNull("cli should be top-level", findComponent(bom, "cli"));
    }

    /**
     * Verifies that classifier/extension are included in the identity used for shaded-only
     * filtering. Two artifacts with the same G:A:V but different classifiers must be treated
     * as distinct: a classifier variant recorded independently must not protect the
     * classifier-less variant from being filtered, and vice versa.
     */
    @Test
    public void classifierVariantsAreDistinctInShadedFiltering() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        // Classifier-less jar is a shaded-only dependency (only in jboss-client, not in modules/)
        final MavenArtifact noClassifier = mavenArtifact("io.netty", "netty-transport-native-unix-common", "4.1.137.Final");

        // linux-x86_64 classifier variant IS independently recorded as a module JAR
        final MavenArtifact linuxClassifier = new MavenArtifact();
        linuxClassifier.setGroupId("io.netty");
        linuxClassifier.setArtifactId("netty-transport-native-unix-common");
        linuxClassifier.setVersion("4.1.137.Final");
        linuxClassifier.setClassifier("linux-x86_64");

        // Only the classifier variant is independently placed in modules/
        recorder.record(linuxClassifier, createArtifactFile(
                "modules/io/netty/main/netty-transport-native-unix-common-4.1.137.Final-linux-x86_64.jar"));
        // classifier-less goes only through ShadedModel (cache + shaded dep)
        recorder.cache(noClassifier, createArtifactFile("external/netty-transport-native-unix-common-4.1.137.Final.jar"));
        recorder.recordShadedComponent("bin/client/jboss-client.jar", "42.0.0",
                createArtifactFile("bin/client/jboss-client.jar"),
                List.of(noClassifier, linuxClassifier));

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        // linux-x86_64 classifier independently recorded -> must be top-level
        assertNotNull("linux-x86_64 classifier variant should be top-level",
                bom.getComponents().stream()
                        .filter(c -> "netty-transport-native-unix-common".equals(c.getName()))
                        .filter(c -> c.getPurl() != null && c.getPurl().contains("linux-x86_64"))
                        .findFirst().orElse(null));
        // classifier-less only went through cache() + shaded dep -> must NOT be top-level
        assertTrue("classifier-less variant should NOT be top-level (shaded-only)",
                bom.getComponents().stream()
                        .filter(c -> "netty-transport-native-unix-common".equals(c.getName()))
                        .noneMatch(c -> c.getPurl() != null && !c.getPurl().contains("classifier=")));
    }


    /**
     * Regression test for the thin-server bug: a module JAR that also happens to be a
     * shaded-JAR dependency must survive filtering when recorded via record(artifact, null)
     * (the thin-server code path where no staged-dir path is available).
     */
    @Test
    public void thinServerModuleJarAlsoInShadedDepStaysTopLevel() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        // Module JAR recorded thin-style (null target — JAR stays in local Maven repo)
        final MavenArtifact moduleJar = mavenArtifact("io.netty", "netty-buffer", "4.1.137.Final");
        recorder.record(moduleJar, null);

        // Same artifact is also a dependency of a shaded JAR (jboss-client.jar scenario)
        recorder.recordShadedComponent("bin/client/jboss-client.jar", "42.0.0",
                createArtifactFile("bin/client/jboss-client.jar"), List.of(moduleJar));

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("netty-buffer should be top-level even in thin mode (it is a module JAR)",
                findComponent(bom, "netty-buffer"));
        assertNotNull("jboss-client shaded JAR should be top-level",
                findComponent(bom, "jboss-client"));
    }

    /**
     * Ordering variant: shaded component is recorded before the module JAR is recorded
     * via record(). The filter must still keep the component because record() asserts
     * independence regardless of call order.
     */
    @Test
    public void shadedDepRecordedBeforeIndependentRecordStaysTopLevel() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact moduleJar = mavenArtifact("org.jboss.xnio", "xnio-api", "3.8.16.Final");

        // Shaded component recorded FIRST — adds xnio-api to shadedDependencyCoords
        recorder.recordShadedComponent("bin/client/jboss-client.jar", "42.0.0",
                createArtifactFile("bin/client/jboss-client.jar"), List.of(moduleJar));

        // Module JAR recorded AFTER — must still mark xnio-api as independent
        recorder.record(moduleJar, null);

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("xnio-api should be top-level regardless of recording order",
                findComponent(bom, "xnio-api"));
    }

    /**
     * An artifact that goes through cache() and is also a shaded dependency must NOT
     * appear as a top-level component. cache() is the ShadedModel resolution path and
     * must not confer independence.
     */
    @Test
    public void cachedArtifactAlsoInShadedDepIsExcludedFromTopLevel() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        final MavenArtifact shadedDep = mavenArtifact("com.google.guava", "guava", "33.0.0-jre");

        // cache() is called by ShadedModel.getArtifacts() when resolving shaded dependencies
        recorder.cache(shadedDep, createArtifactFile("external/guava-33.0.0-jre.jar"));
        recorder.recordShadedComponent("bin/client/jboss-client.jar", "42.0.0",
                createArtifactFile("bin/client/jboss-client.jar"), List.of(shadedDep));

        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertTrue("guava cached via ShadedModel should NOT be a top-level component",
                bom.getComponents().stream().noneMatch(c -> "guava".equals(c.getName())));
        assertNotNull("jboss-client shaded JAR should be top-level",
                findComponent(bom, "jboss-client"));
        // guava must still appear as a nested child of jboss-client
        final Component jbossClient = findComponent(bom, "jboss-client");
        assertNotNull("jboss-client should have nested components", jbossClient.getComponents());
        assertTrue("guava should be a nested child of jboss-client",
                jbossClient.getComponents().stream().anyMatch(c -> "guava".equals(c.getName())));
    }

    @Test
    public void toolDependencyExcludedFromSbom() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.wildfly.core", "wildfly-server", "31.0.0.Final"),
                createArtifactFile("modules/wildfly-server-31.0.0.Final.jar"));
        recorder.recordToolDependency(mavenArtifact("org.wildfly.galleon-plugins", "wildfly-config-gen", "8.0.0.Final"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertNotNull("Distribution artifact should be present", findComponent(bom, "wildfly-server"));
        assertTrue("Tool dependency should NOT appear in SBOM",
                bom.getComponents().stream().noneMatch(c -> "wildfly-config-gen".equals(c.getName())));
    }

    private static Component findComponent(Bom bom, String name) {
        if (bom.getComponents() == null) {
            return null;
        }
        return bom.getComponents().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst().orElse(null);
    }

    @Test
    public void defaultSchemaVersionIsLatestSupported() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");

        recorder.record(mavenArtifact("org.test", "test-lib", "1.0.0"),
                createArtifactFile("lib/test-lib-1.0.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals("Default should be the latest supported CycloneDX schema version",
                SchemaVersions.latest().getVersionString(), bom.getSpecVersion());
    }

    @Test
    public void explicitSchemaVersionIsHonored() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.setSchemaVersion("1.5");

        recorder.record(mavenArtifact("org.test", "test-lib", "1.0.0"),
                createArtifactFile("lib/test-lib-1.0.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals("1.5", bom.getSpecVersion());
    }

    @Test
    public void blankSchemaVersionResolvesToDefault() throws Exception {
        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.setSchemaVersion("   ");

        recorder.record(mavenArtifact("org.test", "test-lib", "1.0.0"),
                createArtifactFile("lib/test-lib-1.0.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals(SchemaVersions.latest().getVersionString(), bom.getSpecVersion());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedSchemaVersionRejected() {
        createRecorder(installBase.resolve("sbom.cdx.json"), "json").setSchemaVersion("9.9");
    }

    @Test
    public void productReleaseFallsBackToStagedProductManifest() throws Exception {
        // When no *-product-conf artifact is present, the release name/version are
        // read (best effort) from the provisioned product module MANIFEST.MF.
        createStagedProductManifest("wildfly-full", "WildFly", "40.0.0.Final", null);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertNotNull(main);
        assertEquals("WildFly", main.getName());
        assertEquals("40.0.0.Final", main.getVersion());
        assertEquals("pkg:generic/wildfly@40.0.0.final", main.getPurl());
    }

    @Test
    public void productCpeReadFromStagedProductManifest() throws Exception {
        // A CPE present in the staged product module manifest is used as-is.
        final String cpe = "cpe:2.3:a:wildfly:wildfly:40.0.0:*:*:*:*:*:*:*";
        createStagedProductManifest("wildfly-full", "WildFly", "40.0.0.Final", cpe);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals(cpe, bom.getMetadata().getComponent().getCpe());
    }

    @Test
    public void productConfArtifactTakesPrecedenceOverStagedManifest() throws Exception {
        createStagedProductManifest("wildfly-full", "WRONG", "0.0.0", null);
        final Path confJar = createProductConfJar(
                "modules/system/layers/base/org/jboss/as/product/main/wildfly-feature-pack-product-conf-40.0.0.Final.jar",
                "WildFly", "40.0.0.Final", "WildFly", "40.0.0.Final");
        final MavenArtifact confArtifact =
                mavenArtifact("org.wildfly", "wildfly-feature-pack-product-conf", "40.0.0.Final");
        confArtifact.setExtension("jar");
        confArtifact.setPath(confJar);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.record(confArtifact, confJar);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        final Component main = bom.getMetadata().getComponent();
        assertEquals("WildFly", main.getName());
        assertEquals("40.0.0.Final", main.getVersion());
    }

    @Test
    public void productCpeOptionSuppliesCpeForStagedManifestFallback() throws Exception {
        // The staged manifest carries no CPE; the configured option supplies it.
        createStagedProductManifest("wildfly-full", "WildFly", "40.0.0.Final", null);
        final String cpe = "cpe:2.3:a:wildfly:wildfly:40.0.0:*:*:*:*:*:*:*";

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.setProductCpe(cpe);
        recorder.record(mavenArtifact("org.test", "test", "1.0"), createArtifactFile("lib/test-1.0.jar"));
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals(cpe, bom.getMetadata().getComponent().getCpe());
    }

    @Test
    public void productCpeOptionOverridesManifestCpe() throws Exception {
        final String manifestCpe = "cpe:2.3:a:wildfly:wildfly:40.0.0:*:*:*:*:*:*:*";
        final String overrideCpe = "cpe:2.3:a:wildfly:wildfly:40.0.0-override:*:*:*:*:*:*:*";
        final Path confJar = createProductConfJar(
                "modules/system/layers/base/org/jboss/as/product/main/wildfly-feature-pack-product-conf-40.0.0.Final.jar",
                "WildFly", "40.0.0.Final", "WildFly", "40.0.0.Final", manifestCpe);
        final MavenArtifact confArtifact =
                mavenArtifact("org.wildfly", "wildfly-feature-pack-product-conf", "40.0.0.Final");
        confArtifact.setExtension("jar");
        confArtifact.setPath(confJar);

        final Path outputFile = installBase.resolve("sbom.cdx.json");
        final SbomArtifactRecorder recorder = createRecorder(outputFile, "json");
        recorder.setProductCpe(overrideCpe);
        recorder.record(confArtifact, confJar);
        recorder.writeManifest();

        final Bom bom = new JsonParser().parse(outputFile.toFile());
        assertEquals(overrideCpe, bom.getMetadata().getComponent().getCpe());
    }

    private Path createStagedProductManifest(String slot, String name, String version, String cpe) throws Exception {
        final Path mf = installBase.resolve(Path.of("modules", "system", "layers", "base",
                "org", "jboss", "as", "product", slot, "dir", "META-INF", "MANIFEST.MF"));
        Files.createDirectories(mf.getParent());
        final Manifest manifest = new Manifest();
        final Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("JBoss-Product-Release-Name", name);
        attrs.putValue("JBoss-Product-Release-Version", version);
        if (cpe != null) {
            attrs.putValue("JBoss-Product-CPE", cpe);
        }
        try (OutputStream os = Files.newOutputStream(mf)) {
            manifest.write(os);
        }
        return mf;
    }

    private static MavenArtifact mavenArtifact(String groupId, String artifactId, String version) {
        final MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId(groupId);
        artifact.setArtifactId(artifactId);
        artifact.setVersion(version);
        return artifact;
    }

    private SbomArtifactRecorder createRecorder(Path outputFile, String format) {
        return new SbomArtifactRecorder(installBase, outputFile, format, false);
    }

    private Path createArtifactFile(String path) throws Exception {
        final Path artifact = installBase.resolve(path);
        if (!Files.exists(artifact.getParent())) {
            Files.createDirectories(artifact.getParent());
        }
        Files.writeString(artifact, "dummy-content-" + path);
        return artifact;
    }

    private Path createProductConfJar(String path, String name, String version,
            String vendor, String buildVersion) throws Exception {
        return createProductConfJar(path, name, version, vendor, buildVersion, null);
    }

    private Path createProductConfJar(String path, String name, String version,
            String vendor, String buildVersion, String cpe) throws Exception {
        final Path jar = installBase.resolve(path);
        Files.createDirectories(jar.getParent());
        final Manifest manifest = new Manifest();
        final Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("JBoss-Product-Release-Name", name);
        attrs.putValue("JBoss-Product-Release-Version", version);
        if (vendor != null) {
            attrs.putValue("Implementation-Vendor", vendor);
        }
        if (buildVersion != null) {
            attrs.putValue("JBossAS-Release-Version", buildVersion);
        }
        if (cpe != null) {
            attrs.putValue("JBoss-Product-CPE", cpe);
        }
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // an empty jar carrying only the branding manifest is sufficient
        }
        return jar;
    }

}
