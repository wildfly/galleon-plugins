/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.PropertyUtils;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 *
 * @author Alexey Loubyansky
 */
public class FilePermissions implements WildFlyPackageTask {

    private Phase phase = Phase.PROCESSING;
    private List<FilePermission> permissions = Collections.emptyList();

    public void setPhase(String phase) {
        this.phase = Phase.valueOf(phase);
    }

    public void addFilePermissions(FilePermission filePermission) {
        permissions = CollectionUtils.add(permissions, filePermission);
    }

    @Override
    public Phase getPhase() {
        return phase;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        if(PropertyUtils.isWindows()) {
            return;
        }

        try {
            final Path installDir = plugin.getRuntime().getStagedDir();
            Files.walkFileTree(installDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    final String relative = installDir.relativize(dir).toString();
                    for (FilePermission perm : permissions) {
                        if (perm.includeFile(relative)) {
                            Files.setPosixFilePermissions(dir, perm.getPermission());
                            continue;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final String relative = installDir.relativize(file).toString();
                    for (FilePermission perm : permissions) {
                        if (perm.includeFile(relative)) {
                            Files.setPosixFilePermissions(file, perm.getPermission());
                            continue;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProvisioningException("Failed to set file permissions", e);
        }
    }
}
