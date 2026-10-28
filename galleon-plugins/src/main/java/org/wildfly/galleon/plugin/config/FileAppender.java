/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 * Task to append content to the `target` file whefre it matches the matcher pattern.
 * If `allMatches` is set to false only the first match will be used.
 * The lines will be added after the first line of the target file that matches the `match` configuration
 * If you need to append some content to the matching line you can set it using `add-to-matching-line`.
 * The lines to be added can be provided either via a file or via a list of lines.
 * You can `ignore` the fact that the target file doesn't exist.
 * @author Emmanuel Hugonnet
 */
public class FileAppender implements WildFlyPackageTask {

    private String src;
    private String target;
    private String match;
    private boolean ignore = true;
    private boolean allMatches = true;
    private final List<String> lines = new ArrayList<>();
    private String addToMatchingLine = null;

    public FileAppender() {
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public Pattern getMatch() {
        if(match == null) {
            return null;
        }
        return Pattern.compile(match);
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public void addLine(String line) {
        if(line == null) {
            this.lines.add("");
        } else {
            this.lines.add(line);
        }
    }

    public boolean isIgnore() {
        return ignore;
    }

    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    public void setSource(String src) {
        this.src = src;
    }

    public void setAddToMatchingLine(String addToMatchingLine) {
        this.addToMatchingLine = addToMatchingLine;
    }

    public void setAllMatches(boolean allMatches) {
        this.allMatches = allMatches;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        if(this.target == null) {
            if(this.isIgnore()) {
                return;
            }
            throw new ProvisioningException("Target can't be null when appending content");
        }
        final Path targetPath = plugin.getRuntime().getStagedDir().resolve(this.target);
        if (!Files.exists(targetPath)) {
            if(this.isIgnore()) {
                return;
            }
            throw new ProvisioningException(Errors.pathDoesNotExist(targetPath));
        }
        try {
            if (this.src != null) {
                final Path srcPath = plugin.getRuntime().getStagedDir().resolve(this.src);
                if (!Files.exists(srcPath)) {
                    throw new ProvisioningException(Errors.pathDoesNotExist(srcPath));
                }
                this.lines.clear();
                this.lines.addAll(Files.readAllLines(srcPath));
            }
            List<String> fileLines = Files.readAllLines(targetPath);
            List<String> updatedLines = new ArrayList<>(lines.size() + 3);
            boolean found = false;
            for (String line : fileLines) {
                if ((allMatches || !found) && this.getMatch() != null && this.getMatch().matcher(line).find()) {
                    if (addToMatchingLine != null && !addToMatchingLine.isEmpty()) {
                        updatedLines.add(line + addToMatchingLine);
                    } else {
                        updatedLines.add(line);
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        updatedLines.add(lines.get(i));
                    }
                } else {
                    updatedLines.add(line);
                }
            }
            if (this.getMatch() == null) {
                updatedLines.addAll(lines);
            }
            Files.write(targetPath, updatedLines, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Failed to append content to file %s", targetPath), e);
        }
    }

}
