/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;


import java.nio.file.attribute.PosixFilePermission;
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

    private Set<PosixFilePermission> permission = Collections.emptySet();
    private String value;
    private List<FileFilter> filters = Collections.emptyList();

    public FilePermission() {
    }

    public void setValue(String value) {
        this.value = value;
        this.permission = fromString(value);
    }

    public void addFilter(FileFilter filter) {
        filters = CollectionUtils.add(filters, filter);
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
