/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileUtils {

    /**
     * Create a zip archive at the {@code zipPath} that contains the directory {@code sourceDirPath}.
     *
     * @param sourceDirPath Path of the source to archive (included in the archive)
     * @param zipPath       Path of the zip archive
     * @throws IOException
     */
    static void zipDirectory(Path sourceDirPath, Path zipPath) throws IOException {
        try (
                ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(zipPath));
                Stream<Path> stream = Files.walk(sourceDirPath)) {
            Path basePath = sourceDirPath.getParent(); // Ensures the root folder is included
            stream.forEach(path -> {
                try {
                    String zipEntryName = basePath.relativize(path).toString().replace("\\", "/");
                    if (Files.isDirectory(path)) {
                        if (!zipEntryName.endsWith("/")) {
                            zipEntryName += "/";
                        }
                        zs.putNextEntry(new ZipEntry(zipEntryName));
                        zs.closeEntry();
                    } else {
                        zs.putNextEntry(new ZipEntry(zipEntryName));
                        Files.copy(path, zs);
                        zs.closeEntry();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }


    static void writeToFile(Path file, String content) throws IOException {
        Path parentDir = file.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        Files.writeString(file, content, UTF_8);
    }
}
