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


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an artifact that is copies into a specific location in the final
 * build.
 *
 *
 * @author Stuart Douglas
 */
public class CopyArtifact {

    public static class Builder {

        private String artifact;
        private String toLocation;
        private boolean extract;
        private List<FileFilter> filters = Collections.emptyList();

        private Builder() {
        }

        public Builder setArtifact(String artifact) {
            this.artifact = artifact;
            return this;
        }

        public Builder setToLocation(String toLocation) {
            this.toLocation = toLocation;
            return this;
        }

        public Builder setExtract() {
            this.extract = true;
            return this;
        }

        public Builder addFilter(FileFilter filter) {
            switch(filters.size()) {
                case 0:
                    filters = Collections.singletonList(filter);
                    break;
                case 1:
                    filters = new ArrayList<FileFilter>(filters);
                default:
                    filters.add(filter);
            }
            return this;
        }

        public CopyArtifact build() {
            return new CopyArtifact(artifact, toLocation, extract,filters);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String artifact;
    private final String toLocation;
    private final boolean extract;
    private final List<FileFilter> filters;


    private CopyArtifact(String artifact, String toLocation, boolean extract, List<FileFilter> filters) {
        this.artifact = artifact;
        this.toLocation = toLocation;
        this.extract = extract;
        this.filters = filters;
    }

    public String getArtifact() {
        return artifact;
    }

    public String getToLocation() {
        return toLocation;
    }

    public boolean isExtract() {
        return extract;
    }

    public List<FileFilter> getFilters() {
        return filters;
    }

    public boolean includeFile(final String path) {
        for(FileFilter filter : filters) {
            if(filter.matches(path)) {
                return filter.isInclude();
            }
        }
        return true; //default include
    }
}
