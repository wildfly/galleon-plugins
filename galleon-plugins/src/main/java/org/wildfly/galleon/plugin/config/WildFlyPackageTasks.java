/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.CollectionUtils;


/**
 *
 * @author Alexey Loubyansky
 */
public class WildFlyPackageTasks {

    public static class Builder {

        private List<CopyArtifact> copyArtifacts = Collections.emptyList();
        private List<CopyPath> copyPaths = Collections.emptyList();
        private List<DeletePath> deletePaths = Collections.emptyList();
        private List<XslTransform> xslTransform = Collections.emptyList();
        private List<FilePermission> filePermissions = Collections.emptyList();
        private List<String> mkDirs = Collections.emptyList();
        private List<FileFilter> windowsLineEndFilters = Collections.emptyList();
        private List<FileFilter> unixLineEndFilters = Collections.emptyList();

        private Builder() {
        }

        public Builder addCopyArtifact(CopyArtifact copy) {
            copyArtifacts = CollectionUtils.add(copyArtifacts, copy);
            return this;
        }

        public Builder addCopyArtifacts(List<CopyArtifact> copyArtifacts) {
            this.copyArtifacts = CollectionUtils.addAll(this.copyArtifacts, copyArtifacts);
            return this;
        }

        public Builder addCopyPath(CopyPath copy) {
            copyPaths = CollectionUtils.add(copyPaths, copy);
            return this;
        }

        public Builder addCopyPaths(List<CopyPath> copyPaths) {
            this.copyPaths = CollectionUtils.addAll(this.copyPaths, copyPaths);
            return this;
        }

        public Builder addDeletePath(DeletePath deletePath) {
            deletePaths = CollectionUtils.add(deletePaths, deletePath);
            return this;
        }

        public Builder addDeletePaths(List<DeletePath> deletePaths) {
            this.deletePaths = CollectionUtils.addAll(this.deletePaths, deletePaths);
            return this;
        }

        public Builder addFilePermissions(FilePermission filePermission) {
            filePermissions = CollectionUtils.add(filePermissions, filePermission);
            return this;
        }

        public Builder addFilePermissions(List<FilePermission> filePermissions) {
            this.filePermissions = CollectionUtils.addAll(this.filePermissions, filePermissions);
            return this;
        }

        public Builder addMkDirs(String mkdirs) {
            mkDirs = CollectionUtils.add(mkDirs, mkdirs);
            return this;
        }

        public Builder addMkDirs(List<String> mkdirs) {
            this.mkDirs = CollectionUtils.addAll(mkDirs, mkdirs);
            return this;
        }

        public void addXslTransform(XslTransform xslTransform) {
            this.xslTransform = CollectionUtils.add(this.xslTransform, xslTransform);
        }

        public void addXslTransform(List<XslTransform> xslTransform) {
            this.xslTransform = CollectionUtils.addAll(this.xslTransform, xslTransform);
        }

        public Builder addWindowsLineEndFilter(FileFilter filter) {
            windowsLineEndFilters = CollectionUtils.add(windowsLineEndFilters, filter);
            return this;
        }

        public Builder addWindowsLineEndFilters(List<FileFilter> filters) {
            this.windowsLineEndFilters = CollectionUtils.addAll(windowsLineEndFilters, filters);
            return this;
        }

        public Builder addUnixLineEndFilter(FileFilter filter) {
            unixLineEndFilters = CollectionUtils.add(unixLineEndFilters, filter);
            return this;
        }

        public Builder addUnixLineEndFilters(List<FileFilter> filters) {
            unixLineEndFilters = CollectionUtils.addAll(unixLineEndFilters, filters);
            return this;
        }

        public WildFlyPackageTasks build() {
            return new WildFlyPackageTasks(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WildFlyPackageTasks load(Path configFile) throws ProvisioningException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            return new WildFlyPackageTasksParser().parse(configStream);
        } catch (XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.openFile(configFile), e);
        }
    }

    private final List<CopyArtifact> copyArtifacts;
    private final List<CopyPath> copyPaths;
    private final List<DeletePath> deletePaths;
    private final List<XslTransform> xslTransform;
    private final List<FilePermission> filePermissions;
    private final List<String> mkDirs;
    private final List<FileFilter> windowsLineEndFilters;
    private final List<FileFilter> unixLineEndFilters;

    private WildFlyPackageTasks(Builder builder) {
        this.copyArtifacts = CollectionUtils.unmodifiable(builder.copyArtifacts);
        this.copyPaths = CollectionUtils.unmodifiable(builder.copyPaths);
        this.deletePaths = CollectionUtils.unmodifiable(builder.deletePaths);
        this.xslTransform = CollectionUtils.unmodifiable(builder.xslTransform);
        this.filePermissions = CollectionUtils.unmodifiable(builder.filePermissions);
        this.mkDirs = CollectionUtils.unmodifiable(builder.mkDirs);
        this.windowsLineEndFilters = CollectionUtils.unmodifiable(builder.windowsLineEndFilters);
        this.unixLineEndFilters = CollectionUtils.unmodifiable(builder.unixLineEndFilters);
    }

    public boolean hasCopyArtifacts() {
        return !copyArtifacts.isEmpty();
    }

    public List<CopyArtifact> getCopyArtifacts() {
        return copyArtifacts;
    }

    public boolean hasCopyPaths() {
        return !copyPaths.isEmpty();
    }

    public List<CopyPath> getCopyPaths() {
        return copyPaths;
    }

    public boolean hasDeletePaths() {
        return !deletePaths.isEmpty();
    }

    public List<DeletePath> getDeletePaths() {
        return deletePaths;
    }

    public boolean hasXslTransform() {
        return !xslTransform.isEmpty();
    }

    public List<XslTransform> getXslTransform() {
        return xslTransform;
    }

    public boolean hasFilePermissions() {
        return !filePermissions.isEmpty();
    }

    public List<FilePermission> getFilePermissions() {
        return filePermissions;
    }

    public boolean hasMkDirs() {
        return !mkDirs.isEmpty();
    }

    public List<String> getMkDirs() {
        return mkDirs;
    }

    public List<FileFilter> getWindowsLineEndFilters() {
        return windowsLineEndFilters;
    }

    public List<FileFilter> getUnixLineEndFilters() {
        return unixLineEndFilters;
    }
}
