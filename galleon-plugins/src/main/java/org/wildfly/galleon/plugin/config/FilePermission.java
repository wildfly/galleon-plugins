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


import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.galleon.util.CollectionUtils;

/**
 * Represents a set of file permissions that should be applied to the final build.
 *
 * @author Stuart Douglas
 * @author Alexey Loubyansky
 */
public class FilePermission {

    public static class Builder {

        private String value;
        private List<FileFilter> filters = Collections.emptyList();

        private Builder() {
        }

        public Builder setValue(String value) {
            this.value = value;
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

        public FilePermission build() {
            return new FilePermission(value, CollectionUtils.unmodifiable(filters));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Set<PosixFilePermission> permission;
    private final String value;
    private final List<FileFilter> filters;

    private FilePermission(String value, List<FileFilter> filters) {
        this.value = value;
        this.permission = fromString(value);
        this.filters = filters;
    }

    private static Set<PosixFilePermission> fromString(String permission) {

        if(permission.length() != 3) {
            throw new RuntimeException("Permission string must be 3 digits");
        }
        final Set<PosixFilePermission> permissions = new HashSet<>();
        int user = Integer.parseInt(Character.toString(permission.charAt(0)));
        if((user & 1) != 0) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if((user & 2) != 0) {
            permissions.add(PosixFilePermission.OWNER_WRITE);
        }
        if((user & 4) != 0) {
            permissions.add(PosixFilePermission.OWNER_READ);
        }
        int group = Integer.parseInt(Character.toString(permission.charAt(1)));
        if((group & 1) != 0) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if((group & 2) != 0) {
            permissions.add(PosixFilePermission.GROUP_WRITE);
        }
        if((group & 4) != 0) {
            permissions.add(PosixFilePermission.GROUP_READ);
        }
        int others = Integer.parseInt(Character.toString(permission.charAt(2)));
        if((others & 1) != 0) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        if((others & 2) != 0) {
            permissions.add(PosixFilePermission.OTHERS_WRITE);
        }
        if((others & 4) != 0) {
            permissions.add(PosixFilePermission.OTHERS_READ);
        }
        return permissions;
    }

    public Set<PosixFilePermission> getPermission() {
        return permission;
    }

    public List<FileFilter> getFilters() {
        return filters;
    }

    public String getValue() {
        return value;
    }

    public boolean includeFile(final String path) {
        for(FileFilter filter : filters) {
            if(filter.matches(path)) {
                return filter.isInclude();
            }
        }
        return false; //default exclude
    }
}
