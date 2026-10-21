/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

/**
 * Property resolver abstract
 *
 * @author Stuart Douglas
 */
public interface PropertyResolver {

    String resolveProperty(String property);
}
