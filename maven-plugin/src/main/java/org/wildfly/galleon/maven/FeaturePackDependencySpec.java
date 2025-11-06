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

package org.wildfly.galleon.maven;

import org.jboss.galleon.config.FeaturePackConfig;

/**
 * Describes a dependency of one feature-pack on another.
 *
 * @author Alexey Loubyansky
 */
public class FeaturePackDependencySpec {

    public static FeaturePackDependencySpec create(FeaturePackConfig fpConfig) {
        return create(null, fpConfig);
    }

    public static FeaturePackDependencySpec create(String name, FeaturePackConfig fpConfig) {
        return new FeaturePackDependencySpec(name, fpConfig);
    }

    public static FeaturePackDependencySpec create(String name, FeaturePackConfig fpConfig, String family) {
        return new FeaturePackDependencySpec(name, fpConfig, family);
    }

    private final String name;
    private final FeaturePackConfig fpConfig;
    private final String allowedFamily;

    private FeaturePackDependencySpec(String name, FeaturePackConfig fpConfig) {
        this(name, fpConfig, null);
    }

    private FeaturePackDependencySpec(String name, FeaturePackConfig fpConfig, String allowedFamily) {
        this.name = name;
        this.fpConfig = fpConfig;
        this.allowedFamily = allowedFamily;
    }

    /**
     * Name of the dependency, which is optional, can be null if the name was not provided
     * by the author of the feature-pack.
     * The name can be used in feature-pack package descriptions to express feature-pack package
     * dependencies on the packages from the feature-pack dependency identified by the name.
     *
     * @return  name of the dependency or null if the dependency was not given a name
     */
    public String getName() {
        return name;
    }

    /**
     * The family in which a member can be used at provisioning time to replace this dependency.
     * @return The allowed family
     */
    public String getAllowedFamily() {
        return allowedFamily;
    }

    /**
     * Description of the feature-pack dependency.
     *
     * @return  dependency description
     */
    public FeaturePackConfig getTarget() {
        return fpConfig;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((fpConfig == null) ? 0 : fpConfig.hashCode());
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FeaturePackDependencySpec other = (FeaturePackDependencySpec) obj;
        if (fpConfig == null) {
            if (other.fpConfig != null)
                return false;
        } else if (!fpConfig.equals(other.fpConfig))
            return false;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append("[dependency ");
        if(name != null) {
            buf.append(name).append(' ');
        }
        return buf.append(fpConfig).append(']').toString();
    }
}
