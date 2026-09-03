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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import dev.cyberstamp.maven.assembly.sbom.AssemblyComponent;
import dev.cyberstamp.maven.assembly.sbom.AssemblyComponents;
import dev.cyberstamp.maven.assembly.sbom.AssemblyMetadata;
import dev.cyberstamp.maven.assembly.sbom.ArtifactCoords;
import dev.cyberstamp.maven.assembly.sbom.BomMerger;
import dev.cyberstamp.maven.assembly.sbom.BomReader;
import dev.cyberstamp.maven.assembly.sbom.BomWriter;
import dev.cyberstamp.maven.assembly.sbom.GenericPackageRef;
import dev.cyberstamp.maven.assembly.sbom.LicenseSource;
import dev.cyberstamp.maven.assembly.sbom.PackageComponent;
import dev.cyberstamp.maven.assembly.sbom.ProductInfo;
import dev.cyberstamp.maven.assembly.sbom.SbomPipeline;
import dev.cyberstamp.maven.assembly.sbom.SchemaVersions;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.model.Bom;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

/**
 * An {@link ArtifactRecorder} that produces a CycloneDX SBOM backed by the
 * assembly-sbom-core neutral component model.
 *
 * <p>Each recorded artifact is accumulated as a {@link PackageComponent} in an
 * {@link AssemblyComponents} model; shaded JARs (assembled on-the-fly from
 * multiple dependencies) become {@link PackageComponent}s with a
 * {@link GenericPackageRef} identity and their dependencies nested underneath.
 * At {@link #writeManifest()} the model is license-enriched (when a
 * {@link LicenseSource} has been set via {@link #setLicenseSource}), rendered to
 * a CycloneDX BOM by {@code BomRenderer}, augmented with any embedded SBOMs found inside resolved
 * JARs, and written by {@link BomWriter}.</p>
 *
 * <p>A dependency that appears only inside a shaded JAR (never recorded
 * independently) is not emitted as a top-level component; it survives only as a
 * nested child of the shaded component.</p>
 */
public class SbomArtifactRecorder implements ArtifactRecorder {

    private static final String MAIN_GROUP_ID = "org.wildfly.galleon-plugins";
    private static final String MAIN_ARTIFACT_ID = "wildfly-galleon-plugins";

    /** A recorded/cached Maven artifact awaiting model assembly. */
    private record RecordedArtifact(ArtifactCoords coords, String archivePath,
            String hash, boolean independent) {
    }

    /** A shaded (assembled) component awaiting model assembly. */
    private record ShadedComponent(String name, String version, String archivePath,
            List<ArtifactCoords> deps) {
    }

    private final Path stagedDir;
    private final Path outputPath;
    private final String format;
    private final boolean prettyPrint;
    private LicenseSource licenseSource;
    private Version schemaVersion;
    private String productCpe;

    private final List<RecordedArtifact> recorded = new ArrayList<>();
    private final List<ShadedComponent> shaded = new ArrayList<>();
    /** Coords recorded independently (module JARs, copy artifacts) via {@link #record}. */
    private final Set<ArtifactCoords> independentCoords = new HashSet<>();
    /** Coords appearing as a dependency of a shaded component. */
    private final Set<ArtifactCoords> shadedDependencyCoords = new HashSet<>();
    /** Resolved JAR paths for embedded-SBOM detection. */
    private final Map<ArtifactCoords, Path> resolvedJarPaths = new LinkedHashMap<>();

    public SbomArtifactRecorder(Path stagedDir, Path outputPath, String format, boolean prettyPrint) {
        this.stagedDir = stagedDir;
        this.outputPath = outputPath;
        this.format = format;
        this.prettyPrint = prettyPrint;
    }

    /**
     * Sets the license source used to enrich components at
     * {@link #writeManifest()}, or {@code null} to skip license resolution.
     *
     * @param licenseSource the license source, or {@code null}
     */
    public void setLicenseSource(LicenseSource licenseSource) {
        this.licenseSource = licenseSource;
    }

    /**
     * Sets the CycloneDX schema version to use for serialization, or
     * {@code null} to use the default (latest supported).
     *
     * @param schemaVersion the version string (e.g. "1.6"), or {@code null}
     */
    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion != null && !schemaVersion.isBlank()
                ? SchemaVersions.resolve(schemaVersion) : null;
    }

    /**
     * Overrides the product CPE of the SBOM's main component. By default the CPE
     * is taken from the product-conf artifact or product module manifest; this
     * override exists for the exceptional case where that CPE is wrong and must be
     * corrected, or where the distribution carries none (e.g. JBoss EAP 7). When
     * {@code null}, the manifest-derived CPE (if any) is used.
     *
     * @param cpe the CPE 2.3 string to force, or {@code null} to use the manifest CPE
     */
    public void setProductCpe(String cpe) {
        this.productCpe = cpe != null && !cpe.isBlank() ? cpe : null;
    }

    @Override
    public void record(MavenArtifact artifact, Path target) throws IOException {
        final ArtifactCoords coords = toCoords(artifact);
        recorded.add(new RecordedArtifact(coords, relativize(target),
                computeHash(resolvedPath(artifact)), true));
        independentCoords.add(coords);
        stashJar(coords, artifact);
    }

    @Override
    public void recordToolDependency(MavenArtifact artifact) {
        // provisioning tools are not part of the distribution — omit from the SBOM
    }

    @Override
    public void cache(MavenArtifact artifact, Path jarSrc) throws MavenUniverseException, IOException {
        final ArtifactCoords coords = toCoords(artifact);
        // cache() is a shaded-model resolution path; it does NOT confer
        // independence, so the artifact is emitted top-level only if also
        // record()ed.
        recorded.add(new RecordedArtifact(coords, null, computeHash(jarSrc), false));
        stashJar(coords, artifact);
    }

    public void recordShadedComponent(String toLocation, String version,
            Path target, List<MavenArtifact> dependencies) {
        final String name = deriveShadedName(toLocation);
        final String archivePath = (target != null && isInsideDistribution(target))
                ? relativize(target) : null;
        final List<ArtifactCoords> deps = new ArrayList<>(dependencies.size());
        for (MavenArtifact dep : dependencies) {
            final ArtifactCoords depCoords = toCoords(dep);
            deps.add(depCoords);
            shadedDependencyCoords.add(depCoords);
        }
        shaded.add(new ShadedComponent(name, version, archivePath, deps));
    }

    @Override
    public void writeManifest() throws IOException {
        // Enrich and render through the shared core pipeline: the jar locator
        // enables shaded-artifact detection (e.g. angus-core inside angus-mail),
        // and the license source (set by the plugin) enables license resolution.
        final ProductRelease release = deriveProductRelease();
        final AssemblyComponents model = buildModel(release);
        final Bom bom = SbomPipeline.forModel(model)
                .jarLocator(resolvedJarPaths::get)
                .licenseSource(licenseSource)
                .render();
        mergeEmbeddedSboms(bom);
        try {
            if (!Files.exists(outputPath.getParent())) {
                Files.createDirectories(outputPath.getParent());
            }
            BomWriter.write(bom, outputPath, format, prettyPrint, schemaVersion);
        } catch (GeneratorException e) {
            throw new IOException("Failed to serialize CycloneDX BOM", e);
        }
    }

    /**
     * Assembles the accumulated recordings into the neutral model. Recorded and
     * cached artifacts become top-level {@link PackageComponent}s (except
     * shaded-only dependencies); each shaded component becomes a
     * {@link GenericPackageRef} component with its dependencies nested.
     */
    private AssemblyComponents buildModel(ProductRelease release) {
        final AssemblyComponents model = new AssemblyComponents();
        // Provisioning records a flat artifact inventory, not a resolved transitive
        // dependency graph, so every component's dependencies are unknown and must
        // not be declared as empty leaf entries in the CycloneDX dependency graph.
        for (RecordedArtifact ra : recorded) {
            if (isShadedOnly(ra.coords())) {
                continue;
            }
            model.addComponent(PackageComponent.of(ra.coords(), ra.archivePath(), ra.hash(), false));
        }
        for (ShadedComponent sc : shaded) {
            final List<AssemblyComponent> nested = new ArrayList<>(sc.deps().size());
            for (ArtifactCoords dep : sc.deps()) {
                nested.add(PackageComponent.of(dep, null, null, false));
            }
            model.addComponent(new PackageComponent(
                    new GenericPackageRef(sc.name(), sc.version()),
                    sc.archivePath(), null, List.of(), nested, false));
        }
        model.setMetadata(buildMetadata(release));
        return model;
    }

    /**
     * A coordinate is shaded-only when it appears as a shaded dependency and was
     * never recorded independently; such an artifact must not be a top-level
     * component. Identity includes classifier and type (via {@link ArtifactCoords}
     * equality), so classifier variants are treated as distinct.
     */
    private boolean isShadedOnly(ArtifactCoords coords) {
        return shadedDependencyCoords.contains(coords) && !independentCoords.contains(coords);
    }

    private AssemblyMetadata buildMetadata(ProductRelease release) {
        final AssemblyMetadata md = new AssemblyMetadata();
        md.setHashAlgorithmSpec("SHA-256");
        // Record wildfly-galleon-plugins as the SBOM-generating tool (rather
        // than the underlying assembly-sbom-core engine used by default).
        md.setToolGroupId(MAIN_GROUP_ID);
        md.setToolArtifactId(MAIN_ARTIFACT_ID);
        md.setToolVersion(resolveToolVersion());
        // Main component: the provisioned product distribution, identified from
        // the distribution's own product-conf branding (WildFly / JBoss EAP).
        if (release != null) {
            md.setProjectArtifactId(release.name());
            md.setProjectVersion(release.version());
            md.setMainComponentPurl(syntheticProductPurl(release.name(), release.version()));
            // The CPE normally comes from the product manifest; a configured CPE
            // overrides it for the exceptional case where it must be corrected.
            final String cpe = productCpe != null ? productCpe : release.cpe();
            if (release.vendor() != null || cpe != null) {
                final ProductInfo product = new ProductInfo();
                if (release.vendor() != null) {
                    product.setPublisher(release.vendor());
                }
                if (cpe != null) {
                    product.setCpe(cpe);
                }
                md.setProduct(product);
            }
        } else {
            // No product branding available (e.g. a non-server provisioning):
            // fall back to a generic distribution identity.
            md.setProjectArtifactId("wildfly");
            md.setMainComponentPurl("pkg:generic/wildfly");
        }
        return md;
    }

    /**
     * Builds a synthetic {@code pkg:generic} purl for the product distribution.
     * A distribution is not a Maven artifact, so its identity is derived from
     * the product release name and version rather than Maven coordinates.
     */
    private static String syntheticProductPurl(String name, String version) {
        return new GenericPackageRef(slug(name), slug(version)).toPurl().toString();
    }

    private static String slug(String value) {
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9.+_-]", "");
    }

    /** Product release branding read from the provisioned distribution. */
    private record ProductRelease(String name, String version, String vendor, String cpe) {
    }

    /**
     * Derives the product release (name, version, vendor, build id) from the
     * distribution's product-conf artifact. WildFly/EAP feature packs contribute
     * an {@code org.jboss.as.product} module whose content is a Maven artifact
     * (artifactId ending {@code -product-conf}) whose manifest carries
     * {@code JBoss-Product-Release-Name}/{@code -Version} and, optionally,
     * {@code JBoss-Product-CPE}. That artifact is
     * recorded as a module dependency, so this works in both a full provision
     * and SBOM-only mode (where no modules or files are installed). Returns
     * {@code null} when no such artifact is present.
     */
    private ProductRelease deriveProductRelease() {
        for (Map.Entry<ArtifactCoords, Path> entry : resolvedJarPaths.entrySet()) {
            if (!isProductConfArtifact(entry.getKey())) {
                continue;
            }
            final ProductRelease release = productReleaseFromManifest(readJarManifest(entry.getValue()));
            if (release != null) {
                return release;
            }
        }
        // No product-conf artifact (e.g. JBoss EAP 7): best-effort fallback to the
        // provisioned product module manifest. This is only available in a full
        // provision — in SBOM-only mode no modules are installed, so it yields no
        // product branding.
        return readStagedProductManifest();
    }

    /**
     * Builds a {@link ProductRelease} from a product manifest's main attributes,
     * or {@code null} when the required release name/version are absent. Shared by
     * the product-conf artifact and the provisioned product module manifest.
     */
    private static ProductRelease productReleaseFromManifest(Attributes attrs) {
        if (attrs == null) {
            return null;
        }
        final String name = attrs.getValue("JBoss-Product-Release-Name");
        final String version = attrs.getValue("JBoss-Product-Release-Version");
        if (name == null || version == null) {
            return null;
        }
        return new ProductRelease(name, version,
                attrs.getValue("Implementation-Vendor"),
                attrs.getValue("JBoss-Product-CPE"));
    }

    /**
     * Reads the provisioned product module manifest
     * ({@code modules/system/layers/base/org/jboss/as/product/<slot>/dir/META-INF/MANIFEST.MF})
     * from the staged distribution, returning the first slot that carries a
     * release name/version, or {@code null} if none is present.
     */
    private ProductRelease readStagedProductManifest() {
        final Path productDir = stagedDir.resolve(
                Path.of("modules", "system", "layers", "base", "org", "jboss", "as", "product"));
        if (!Files.isDirectory(productDir)) {
            return null;
        }
        try (Stream<Path> slots = Files.list(productDir)) {
            for (Path slot : (Iterable<Path>) slots::iterator) {
                final Path mf = slot.resolve(Path.of("dir", "META-INF", "MANIFEST.MF"));
                if (!Files.isRegularFile(mf)) {
                    continue;
                }
                final ProductRelease release = productReleaseFromManifest(readManifestFile(mf));
                if (release != null) {
                    return release;
                }
            }
        } catch (IOException e) {
            // best-effort: no product branding
        }
        return null;
    }

    private static Attributes readManifestFile(Path manifestFile) {
        try (InputStream is = Files.newInputStream(manifestFile)) {
            return new Manifest(is).getMainAttributes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether a recorded artifact is the product-conf artifact that brands the
     * distribution, identified by the WildFly/EAP {@code *-product-conf}
     * artifactId convention on a plain (unclassified) JAR.
     */
    private static boolean isProductConfArtifact(ArtifactCoords coords) {
        final String artifactId = coords.artifactId();
        return artifactId != null && artifactId.endsWith("-product-conf")
                && "jar".equals(coords.type())
                && (coords.classifier() == null || coords.classifier().isEmpty());
    }

    private static Attributes readJarManifest(Path jar) {
        if (jar == null || !Files.exists(jar)) {
            return null;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            final Manifest mf = jf.getManifest();
            return mf != null ? mf.getMainAttributes() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Merges any CycloneDX SBOM embedded inside a resolved JAR under that JAR's
     * component in the rendered BOM. The parent bom-ref is the component's PURL,
     * matching how {@link BomRenderer} assigns bom-refs.
     */
    private void mergeEmbeddedSboms(Bom bom) {
        for (var entry : resolvedJarPaths.entrySet()) {
            final Path jarPath = entry.getValue();
            if (jarPath == null || !Files.exists(jarPath)) {
                continue;
            }
            try {
                final Bom embedded = scanForEmbeddedSbom(jarPath);
                if (embedded == null) {
                    continue;
                }
                final String parentRef = entry.getKey().toPurl().toString();
                if (BomMerger.findComponentByBomRef(bom, parentRef) != null) {
                    BomMerger.mergeUnder(bom, parentRef, embedded);
                }
            } catch (Exception e) {
                // best-effort embedded-SBOM merge
            }
        }
    }

    private Bom scanForEmbeddedSbom(Path jarPath) {
        if (!jarPath.getFileName().toString().endsWith(".jar")) {
            return null;
        }
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            try (Stream<Path> walk = Files.walk(root)) {
                return walk
                        .filter(p -> {
                            String name = p.getFileName() == null ? "" : p.getFileName().toString();
                            return name.endsWith(".cdx.json") || name.endsWith(".cdx.xml");
                        })
                        .findFirst()
                        .map(p -> {
                            try (InputStream is = Files.newInputStream(p)) {
                                return BomReader.readBom(is);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void stashJar(ArtifactCoords coords, MavenArtifact artifact) {
        final Path resolved = resolvedPath(artifact);
        if (resolved != null && Files.exists(resolved)) {
            resolvedJarPaths.put(coords, resolved);
        }
    }

    private Path resolvedPath(MavenArtifact artifact) {
        try {
            return artifact.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isInsideDistribution(Path target) {
        return target.toAbsolutePath().startsWith(stagedDir.toAbsolutePath());
    }

    private String relativize(Path target) {
        if (target == null) {
            return null;
        }
        final Path absTarget = target.toAbsolutePath();
        final Path absStagedDir = stagedDir.toAbsolutePath();
        if (!absTarget.startsWith(absStagedDir)) {
            return null;
        }
        return absStagedDir.relativize(absTarget).toString().replace(File.separatorChar, '/');
    }

    private String deriveShadedName(String toLocation) {
        String fileName = toLocation;
        final int lastSlash = toLocation.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileName = toLocation.substring(lastSlash + 1);
        }
        if (fileName.endsWith(".jar")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    static ArtifactCoords toCoords(MavenArtifact artifact) {
        return new ArtifactCoords(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getExtension(),
                artifact.getClassifier());
    }

    private static String computeHash(Path file) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    private static String resolveToolVersion() {
        final String version = WfInstallPlugin.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
