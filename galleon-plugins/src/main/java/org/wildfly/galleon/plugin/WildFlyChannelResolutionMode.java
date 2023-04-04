/*
 * Copyright 2016-2023 Red Hat, Inc. and/or its affiliates
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