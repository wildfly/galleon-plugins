/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin;

import java.util.Map;

/**
 * @author Stuart Douglas
 */
public class MapPropertyResolver implements PropertyResolver {

    private final Map<String, String> props;

    public MapPropertyResolver(Map<String, String> props) {
        this.props = props;
    }

    @Override
    public String resolveProperty(String property) {
        return props.get(property);
    }
}
