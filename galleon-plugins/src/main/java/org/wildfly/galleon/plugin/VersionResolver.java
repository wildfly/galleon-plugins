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
package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.jboss.galleon.model.Gaec;
import org.jboss.galleon.model.Gaecv;

/**
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 *
 */
public class VersionResolver {

    public static class Builder {
        private final Properties gaToGavMap = new Properties();
        public VersionResolver build() {
            return new VersionResolver(gaToGavMap);
        }
        public Builder load(Path path) throws IOException {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    gaToGavMap.load(in);
                }
            }
            return this;
        }
    }

    private final Map<?,?> gaToGavMap;

    VersionResolver(Map<?, ?> gaToGavMap) {
        super();
        this.gaToGavMap = gaToGavMap;
    }

    public Gaecv resolveVersion(Gaec gaec) {
        for (int i = 4; i >=1 ; i--) {
            Object val = gaToGavMap.get(gaec.toString(i));
            if (val instanceof String) {
                String[] segments = ((String) val).split(":");
                if (segments.length >= 2) {
                    return new Gaecv(gaec, segments[2]);
                } else {
                    throw new IllegalStateException("Cannot resolve version of "+ gaec + ": mapped string '"+ val +"' has bad format; version expected after second colon");
                }
            }
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }
}
