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

import java.util.Collections;
import java.util.Map;

import org.jboss.galleon.util.CollectionUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class XslTransform {

    public static class Builder {

        private String stylesheet;
        private String src;
        private String output;
        private Map<String, String> params = Collections.emptyMap();

        private Builder() {
        }

        public Builder setSrc(String src) {
            this.src = src;
            return this;
        }

        public Builder setOutput(String output) {
            this.output = output;
            return this;
        }

        public Builder setStylesheet(String stylesheet) {
            this.stylesheet = stylesheet;
            return this;
        }

        public void setParam(String name, String value) {
            params = CollectionUtils.put(params, name, value);
        }

        public XslTransform build() {
            return new XslTransform(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String src;
    private final String stylesheet;
    private final String output;
    private final Map<String, String> params;

    private XslTransform(Builder builder) {
        this.src = builder.src;
        this.stylesheet = builder.stylesheet;
        this.output = builder.output;
        this.params = CollectionUtils.unmodifiable(builder.params);
    }

    public String getSrc() {
        return src;
    }

    public String getStylesheet() {
        return stylesheet;
    }

    public String getOutput() {
        return output;
    }

    public boolean hasParams() {
        return !params.isEmpty();
    }

    public Map<String, String> getParams() {
        return params;
    }
}
