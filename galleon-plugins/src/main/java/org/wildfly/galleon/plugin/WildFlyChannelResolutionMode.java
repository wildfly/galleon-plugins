/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

/**
 * Feature-pack WildFly channel resolution mode when WildFly channels are configured.
 * NOT_REQUIRED means that the feature-pack and artifacts can be resolved without channels.
 * REQUIRED means that the feature-pack and all its artifacts must be only resolved from channels.
 * REQUIRED_FP_ONLY means that only the feature-pack must be only resolved from channels.
 * Referenced artifacts can be resolved outside of configured WildFly channels.
 */
public enum WildFlyChannelResolutionMode {
    NOT_REQUIRED,
    REQUIRED,
    REQUIRED_FP_ONLY
}