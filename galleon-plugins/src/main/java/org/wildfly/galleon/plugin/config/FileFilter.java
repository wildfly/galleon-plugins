/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.config;


import java.util.regex.Pattern;

import org.jboss.galleon.util.ParsingUtils;

/**
 * @author Stuart Douglas
 * @author Alexey Loubyansky
 */
public class FileFilter {

    private String patternString;
    private Pattern pattern;
    private boolean include;

    public FileFilter() {
    }

    public void setPatternString(String patternString) {
        this.patternString = patternString;
        this.pattern = Pattern.compile(ParsingUtils.wildcardToJavaRegexp(patternString));
    }

    public void setInclude() {
        this.include = true;
    }

    public String getPattern() {
        return patternString;
    }

    /**
     * Returns true if the file matches the regular expression
     */
    public boolean matches(final String filePath) {
        return pattern.matcher(filePath).matches();
    }

    public boolean isInclude() {
        return include;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FileFilter that = (FileFilter) o;

        if (!patternString.equals(that.patternString)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return patternString.hashCode();
    }

    @Override
    public String toString() {
        return patternString;
    }
}
