/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

/**
 * The CPE of a feature-pack is the one from the `server *-product-conf artifact` or is `local` to the feature-pack.
 * `local` doesn't imply that a CPE is defined (could be) in the feature-pack. `local` implies that the cpe is different from
 * the `product-conf` one.
 */
public enum CpeResolutionMode {
    SERVER_PRODUCT_CONF,
    LOCAL
};
