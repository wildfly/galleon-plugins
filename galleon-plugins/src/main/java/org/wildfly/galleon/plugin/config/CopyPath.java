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

/**
 *
 * @author Alexey Loubyansky
 */
public class CopyPath {

    public static class Builder {

        private String src;
        private String target;
        private boolean replaceProperties;

        private Builder() {
        }

        public Builder setSrc(String src) {
            this.src = src;
            return this;
        }

        public Builder setTarget(String target) {
            this.target = target;
            return this;
        }

        public Builder setReplaceProperties(boolean replaceProperties) {
            this.replaceProperties = replaceProperties;
            return this;
        }

        public CopyPath build() {
            return new CopyPath(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String src;
    private final String target;
    private final boolean replaceProperties;

    private CopyPath(Builder builder) {
        this.src = builder.src;
        this.target = builder.target;
        this.replaceProperties = builder.replaceProperties;
    }

    public String getSrc() {
        return src;
    }

    public String getTarget() {
        return target;
    }

    public boolean isReplaceProperties() {
        return replaceProperties;
    }
}
