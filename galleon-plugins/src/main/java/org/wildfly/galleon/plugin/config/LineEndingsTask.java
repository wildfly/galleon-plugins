/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

public class LineEndingsTask implements WildFlyPackageTask {

   private final List<FileFilter> unixLineEndFilters;
   private final List<FileFilter> windowsLineEndFilters;
   private Phase phase;

   public LineEndingsTask(List<FileFilter> unixLineEndFilters, List<FileFilter> windowsLineEndFilters, Phase phase) {
      this.unixLineEndFilters = unixLineEndFilters;
      this.windowsLineEndFilters = windowsLineEndFilters;
      this.phase = phase;
   }

   @Override
   public Phase getPhase() {
      return phase;
   }

   @Override
   public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
      final Path installDir = plugin.getRuntime().getStagedDir();

      // If not line end filters are present no need to walk the directory
      if (unixLineEndFilters.isEmpty() && windowsLineEndFilters.isEmpty()) {
         return;
      }
      try {
         Files.walkFileTree(installDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
               final String relative = installDir.relativize(file).toString();
               for (FileFilter filter : unixLineEndFilters) {
                  if (filter.matches(relative)) {
                     try {
                        changeLineEndings(file, false);
                     } catch (IOException e) {
                        throw new UncheckedIOException(String.format("Failed to convert %s to Unix line endings.", file), e);
                     }
                  }
               }
               for (FileFilter filter : windowsLineEndFilters) {
                  if (filter.matches(relative)) {
                     try {
                        changeLineEndings(file, true);
                     } catch (IOException e) {
                        throw new UncheckedIOException(String.format("Failed to convert %s to Windows line endings.", file), e);
                     }
                  }
               }
               return FileVisitResult.CONTINUE;
            }
         });
      } catch (UncheckedIOException e) {
         throw new ProvisioningException(e.getMessage(), e.getCause());
      } catch (IOException e) {
         throw new ProvisioningException(String.format("Failed to process %s for files that require line ending changes.", installDir), e);
      }

   }

   private static void changeLineEndings(final Path file, final boolean isWindows) throws IOException {
      final String eol = (isWindows ? "\r\n" : "\n");
      final Path temp = Files.createTempFile(file.getFileName().toString(), ".tmp");
      // Copy the original file to the temporary file, replacing it and copying the attributes. Note that the order of
      // REPLACE_EXISTING and COPY_ATTRIBUTES is important.
      Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      try (
         BufferedReader reader = Files.newBufferedReader(temp, StandardCharsets.UTF_8);
         BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
      ) {
         // Process each line and write a eol string
         String line;
         while ((line = reader.readLine()) != null) {
            writer.write(line);
            writer.write(eol);
         }

      } finally {
         Files.delete(temp);
      }
   }
}
