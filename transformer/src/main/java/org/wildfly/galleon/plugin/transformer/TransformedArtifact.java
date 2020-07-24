/*
 * Copyright 2016-2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin.transformer;

import java.nio.file.Path;
/**
 *
 * @author jdenise
 */
public class TransformedArtifact {
    private final Path src;
    private final Path target;
    private final boolean transformed;
    private final boolean srcSigned;
    private final boolean unsigned;

    TransformedArtifact(Path src, Path target, boolean transformed, boolean srcSigned, boolean unsigned) {
        this.src = src;
        this.target = target;
        this.transformed = transformed;
        this.srcSigned = srcSigned;
        this.unsigned = unsigned;
    }

    /**
     * @return the src
     */
    public Path getSrc() {
        return src;
    }

    /**
     * @return the target
     */
    public Path getTarget() {
        return target;
    }

    /**
     * @return the transformed
     */
    public boolean isTransformed() {
        return transformed;
    }

    /**
     * @return the unsigned
     */
    public boolean isUnsigned() {
        return unsigned;
    }

    /**
     * @return the srcSigned
     */
    public boolean isSrcSigned() {
        return srcSigned;
    }

}
