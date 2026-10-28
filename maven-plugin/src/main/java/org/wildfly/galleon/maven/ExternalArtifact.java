/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven;

import org.codehaus.plexus.util.StringUtils;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ExternalArtifact {

    private ArtifactItem artifact;
    private String toLocation;
    private boolean extract;
    private String includes;
    private String excludes;

    public ExternalArtifact() {
    }

    public ArtifactItem getArtifactItem() {
        return artifact;
    }

    public void setArtifactItem(ArtifactItem artifact) {
        this.artifact = artifact;
    }

    public String getToLocation() {
        return toLocation;
    }

    public void setToLocation(String toLocation) {
        this.toLocation = toLocation;
    }

    public boolean isExtract() {
        return extract;
    }

    public void setExtract(boolean extract) {
        this.extract = extract;
    }

    public String getIncludes() {
        return includes;
    }

    public void setIncludes(String includes) {
        this.includes = cleanToBeTokenizedString(includes);
    }

    public String getExcludes() {
        return excludes;
    }

    public void setExcludes(String excludes) {
        this.excludes = cleanToBeTokenizedString(excludes);
    }

    private static String cleanToBeTokenizedString(String str) {
        String ret = "";
        if (!StringUtils.isEmpty(str)) {
            // remove initial and ending spaces, plus all spaces next to commas
            ret = str.trim().replaceAll("[\\s]*,[\\s]*", ",");
        }
        return ret;
    }
}
