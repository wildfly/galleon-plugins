/*
 * Copyright 2016-2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin.transformer;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 *
 *
 * @author jdenise
 */
class JarUtils {

    private static final Path MANIFEST_DIR = Paths.get("META-INF");
    private static final Path MANIFEST_FILE = MANIFEST_DIR.resolve("MANIFEST.MF");

    static boolean isSignedJar(Path jarFile) throws IOException {
        JarFile jar = new JarFile(jarFile.toFile());
        Manifest manifest = jar.getManifest();

        boolean signed = false;
        if (manifest != null) {
            for (Map.Entry<String, Attributes> entry : manifest.getEntries().entrySet()) {
                for (Object attrkey : entry.getValue().keySet()) {
                    if (attrkey instanceof Attributes.Name
                            && ((Attributes.Name) attrkey).toString().indexOf("-Digest") != -1) {
                        signed = true;
                        break;
                    }
                }
            }
        }
        return signed;
    }

    // We can't use Jar API to unsign, the jar to unsign can't be verified.
    static void unsign(Path jarFile) throws Exception {
        FileInputStream fi = new FileInputStream(jarFile.toFile());
        Path dir = Files.createTempDirectory("unsign-jar-" + jarFile.getFileName());
        Utils.unzip(fi, dir.toFile());
        visitJar(dir);
        fi.close();
        Files.delete(jarFile);
        // Do not depend on galleon-core ZipUtils.
        Utils.zip(dir, jarFile);
        // Do not depend on galleon-core IoUtils.
        Utils.recursiveDelete(dir);
    }

    private static void visitJar(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path f = dir.relativize(file);
                if (MANIFEST_DIR.equals(f.getParent())) {
                    if (MANIFEST_FILE.equals(f)) {
                        transformManifest(file);
                    } else {
                        if (f.toString().endsWith(".SF") || f.toString().endsWith(".RSA") || f.toString().endsWith(".DSA")) {
                            Files.delete(file);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void transformManifest(Path manifestFile) throws IOException {
        FileInputStream fi = new FileInputStream(manifestFile.toFile());
        Manifest manifest2 = new Manifest();
        try {
            Manifest manifest = new Manifest(fi);

            for (Map.Entry<Object, Object> entry : manifest.getMainAttributes().entrySet()) {
                manifest2.getMainAttributes().put(entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Attributes> entry : manifest.getEntries().entrySet()) {
                boolean toRemove = false;
                for (Object attrkey : entry.getValue().keySet()) {
                    if (attrkey instanceof Attributes.Name
                            && ((Attributes.Name) attrkey).toString().indexOf("-Digest") != -1) {
                        toRemove = true;
                        break;
                    }
                }
                if (!toRemove) {
                    manifest2.getEntries().put(entry.getKey(), entry.getValue());
                }
            }

        } finally {
            fi.close();
            Files.delete(manifestFile);
            try (FileOutputStream out = new FileOutputStream(manifestFile.toFile())) {
                manifest2.write(out);
            }
        }
    }
}
