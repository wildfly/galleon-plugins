/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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
 *
 * @author Alexey Loubyansky
 */
public interface WfConstants {

    // Artifacts
    String WF_CONFIG_GEN = "wildfly-config-gen";
    String WF_GALLEON_PLUGINS = "wildfly-galleon-plugins";

    String ADD_ONS = "add-ons";
    String ARTIFACT_VERSIONS_PROPS = "artifact-versions.properties";
    String BASE = "base";
    String CONFIG = "config";
    String CONFIGURATION = "configuration";
    String CONTENT = "content";
    String DOCS = "docs";
    String DOCS_LICENSES = "docs.licenses";
    String DOCS_SCHEMA = "docs.schema";
    String DOT_JAR = ".jar";
    String DOMAIN = "domain";
    String EXTENSIONS_DOMAIN = "extensions-domain";
    String EXTENSIONS_HOST = "extensions-host";
    String EXTENSIONS_STANDALONE = "extensions-standalone";
    String GALLEON_PASSIVE = "galleon.passive";
    String HOST = "host";
    String JBOSS_STABILITY = "jboss.stability";
    String LAYOUT = "layout";
    String LAYERS = "layers";
    String LAYERS_CONF = "layers.conf";
    String MODULE = "module";
    String MODULE_XML = "module.xml";
    String MODULES = "modules";
    String MODULES_ALL = "modules.all";
    String PM = "pm";
    String PROFILE = "profile";
    String SCHEMA = "schema";
    String SCHEMA_GROUPS_TXT = "schema-groups.txt";
    String SCRIPTS = "scripts";
    String STANDALONE = "standalone";
    String STEPS = "steps";
    String SYSTEM = "system";
    String TASKS_XML = "tasks.xml";
    String UTF8 = "UTF-8";
    String VALUE = "value";
    String WILDFLY = "wildfly";
    String WILDFLY_CHANNEL_PROPS = "wildfly-channel.properties";
    String WILDFLY_CHANNEL_RESOLUTION_PROP = "resolution";
    String WILDFLY_TASKS_PROPS = "wildfly-tasks.properties";
    String WILDFLY_TASKS_XML = "wildfly-tasks.xml";

    // Feature annotation names and elements
    String ADD = "add";
    String ADDR_PARAMS = "addr-params";
    String ADDR_PARAMS_MAPPING = "addr-params-mapping";
    String JBOSS_OP = "jboss-op";
    String LIST_ADD = "list-add";
    String NAME = "name";
    String OP_PARAMS = "op-params";
    String OP_PARAMS_MAPPING = "op-params-mapping";
    String WRITE_ATTRIBUTE = "write-attribute";

    String EMBEDDED_ARG_DOMAIN_CONFIG = "--domain-config";
    String EMBEDDED_ARG_EMPTY_DOMAIN_CONFIG = "--empty-domain-config";
    String EMBEDDED_ARG_EMPTY_HOST_CONFIG = "--empty-host-config";
    String EMBEDDED_ARG_HOST_CONFIG = "--host-config";
    String EMBEDDED_ARG_INTERNAL_EMPTY_CONFIG = "--internal-empty-config";
    String EMBEDDED_ARG_SERVER_CONFIG = "--server-config";
}
