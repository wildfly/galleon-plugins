/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.util.Objects;

/**
 * SBOM identity of a feature-pack that is reported as a framework. A
 * {@link CpeResolutionMode#LOCAL} feature-pack that defines a cpe, a name and a purl has such an identity.
 * The publisher is optional.
 * It is reported in the SBOM as a framework that provides the artifacts it contributes.
 */
class FrameworkInfo {

    static final String UNKNOWN_VERSION = "unknown";

    private final String cpe;
    private final String name;
    private final String purl;
    private final String publisher;
    private final String version;

    /**
     * @param publisher the publisher, can be {@code null}.
     * @param version the feature-pack version. The version of the purl, if it has one, is the version of the
     * framework. Otherwise {@code version} is used, or {@link #UNKNOWN_VERSION} if it is {@code null}, and is
     * appended to the purl.
     */
    FrameworkInfo(String cpe, String name, String purl, String publisher, String version) {
        this.cpe = Objects.requireNonNull(cpe);
        this.name = Objects.requireNonNull(name);
        this.publisher = publisher;
        final int at = purl.indexOf('@');
        if (at >= 0) {
            this.version = purlVersion(purl, at);
            this.purl = purl;
        } else {
            this.version = version == null ? UNKNOWN_VERSION : version.toLowerCase();
            this.purl = purl + "@" + this.version;
        }
    }

    /** The version of a purl, without its qualifiers and subpath. */
    private static String purlVersion(String purl, int at) {
        int end = purl.length();
        for (char c : new char[] {'?', '#'}) {
            final int i = purl.indexOf(c, at);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return purl.substring(at + 1, end);
    }

    /**
     * @param mode the resolution mode, {@code null} is the same as {@link CpeResolutionMode#LOCAL}.
     * @return the identity of the feature-pack, or {@code null} if the feature-pack has none: its resolution mode
     * is not {@link CpeResolutionMode#LOCAL}, or the cpe, the name or the purl is not set. The name and the purl
     * are always set by the {@code build-feature-pack} goal when it sets a cpe.
     */
    static FrameworkInfo of(CpeResolutionMode mode, String cpe, String name, String purl, String publisher, String version) {
        if ((mode != null && !CpeResolutionMode.LOCAL.equals(mode)) || cpe == null || name == null || purl == null) {
            return null;
        }
        return new FrameworkInfo(cpe, name, purl, publisher, version);
    }

    String getCpe() {
        return cpe;
    }

    String getName() {
        return name;
    }

    String getPurl() {
        return purl;
    }

    /** @return the publisher, {@code null} if not set. */
    String getPublisher() {
        return publisher;
    }

    String getVersion() {
        return version;
    }
}
