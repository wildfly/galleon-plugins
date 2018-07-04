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

import static org.jboss.galleon.Constants.GLN_UNDEFINED;
import static org.wildfly.galleon.plugin.WfConstants.ADDR_PARAMS;
import static org.wildfly.galleon.plugin.WfConstants.ADDR_PARAMS_MAPPING;
import static org.wildfly.galleon.plugin.WfConstants.OP_PARAMS;
import static org.wildfly.galleon.plugin.WfConstants.OP_PARAMS_MAPPING;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeatureConfig;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.plugin.PluginOption;
import org.jboss.galleon.runtime.FeaturePackRuntime;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.spec.FeatureId;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.universe.FeaturePackLocation.FPID;
import org.wildfly.galleon.plugin.server.CompleteServerInvoker;
import org.wildfly.galleon.plugin.server.EmbeddedServerInvoker;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class WfDiffConfigGenerator {

    private static final String CONFIGURE_SYNC = "/synchronization=simple:add(host=%s, port=%s, protocol=%s, username=%s, password=%s)";
    private static final String EXPORT_DIFF = "attachment save --overwrite --operation=/synchronization=simple:export-diff --file=%s";
    private static final String EXPORT_FEATURE = "attachment save --overwrite --operation=/synchronization=simple:feature-diff --file=%s";

    static final PluginOption HOST = PluginOption.builder("host").setDefaultValue("127.0.0.1").build();
    static final PluginOption PORT = PluginOption.builder("port").setDefaultValue("9990").build();
    static final PluginOption PROTOCOL = PluginOption.builder("protocol").setDefaultValue("remote+http").build();
    static final PluginOption USERNAME = PluginOption.builder("username").setRequired().build();
    static final PluginOption PASSWORD = PluginOption.builder("password").setRequired().build();
    static final PluginOption SERVER_CONFIG = PluginOption.builder("server-config").setDefaultValue("standalone.xml").build();

    public static ConfigModel exportDiff (ProvisioningRuntime runtime, Map<FPID, ConfigId> includedConfigs, Path customizedInstallation, Path target) throws ProvisioningException {
        String host = runtime.getOptionValue(HOST);
        String port = runtime.getOptionValue(PORT);
        String protocol = runtime.getOptionValue(PROTOCOL);
        String username = runtime.getOptionValue(USERNAME);
        String password = runtime.getOptionValue(PASSWORD);
        String serverConfig = runtime.getOptionValue(SERVER_CONFIG);
        CompleteServerInvoker server = new CompleteServerInvoker(customizedInstallation.toAbsolutePath(), runtime.getMessageWriter(), serverConfig);
        EmbeddedServerInvoker embeddedServer = new EmbeddedServerInvoker(runtime.getMessageWriter(), runtime.getInstallDir().toAbsolutePath(), serverConfig);
        try {
            Files.createDirectories(target);
            server.startServer();
            embeddedServer.execute(
                    String.format(CONFIGURE_SYNC, host, port, protocol, username, password),
                    String.format(EXPORT_DIFF, target.resolve("finalize.cli").toAbsolutePath()),
                    String.format(EXPORT_FEATURE, target.resolve("feature_config.dmr").toAbsolutePath()));
            ConfigModel.Builder configBuilder = ConfigModel.builder().setName("standalone.xml").setModel("standalone");
            createConfiguration(runtime, configBuilder, includedConfigs, target.resolve("feature_config.dmr").toAbsolutePath());
            return configBuilder.build();
        } catch (IOException | XMLStreamException ex) {
            runtime.getMessageWriter().error(ex, "Couldn't compute the WildFly Model diff because of %s", ex.getMessage());
            throw new ProvisioningException("Couldn't compute the WildFly Model diff", ex);
        } finally {
            server.stopServer();
        }
    }

    private static void createConfiguration(ProvisioningRuntime runtime, ConfigModel.Builder builder,
            Map<FPID, ConfigId> includedConfigBuilders, Path json)
            throws IOException, XMLStreamException, ProvisioningDescriptionException {
        try (InputStream in = Files.newInputStream(json)) {
            ModelNode featureDiff = ModelNode.fromBase64(in);
            for (ModelNode feature : featureDiff.asList()) {
                String specName = feature.require("feature").require("spec").asString();
                DependencySpec dependencySpec = getFeatureSpec(runtime, specName);
                FeatureSpec resolvedSpec = dependencySpec.spec;
                if (resolvedSpec != null && resolvedSpec.hasAnnotations()) {
                    Map<String, String> address = new HashMap<>();
                    for (Property elt : feature.require("feature").require("address").asPropertyList()) {
                        address.put(elt.getName(), elt.getValue().asString());
                    }
                    FeatureConfig featureConfig = FeatureConfig.newConfig(specName).setOrigin(dependencySpec.fpName);
                    final FeatureAnnotation firstAnnotation = resolvedSpec.getAnnotations().iterator().next();
                    resolveAddressParams(featureConfig, address, firstAnnotation);
                    Map<String, String> params = new HashMap<>();
                    if (feature.require("feature").hasDefined("params")) {
                        for (Property elt : feature.require("feature").require("params").asPropertyList()) {
                            params.put(elt.getName(), elt.getValue().asString());
                        }
                        resolveParams(featureConfig, params, firstAnnotation);
                    }
                    if (feature.require("feature").require("exclude").asBoolean()) {
                        if (!includedConfigBuilders.containsKey(dependencySpec.fpid)) {
                            includedConfigBuilders.put(dependencySpec.fpid, new ConfigId("standalone", "standalone.xml"));
                        }
                        FeatureId.Builder idBuilder = FeatureId.builder(specName);
                        for(FeatureParameterSpec fparam : resolvedSpec.getIdParams()) {
                            idBuilder.setParam(fparam.getName(), featureConfig.getParam(fparam.getName()));
                        }
                        builder.excludeFeature(dependencySpec.fpName, idBuilder.build());
                    } else {
                        builder.addFeature(featureConfig);
                    }
                }
            }
        }
    }

    private static DependencySpec getFeatureSpec(ProvisioningRuntime runtime, String name) throws ProvisioningDescriptionException {
        for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            ResolvedFeatureSpec spec = fp.getResolvedFeatureSpec(name);
            if (spec != null) {
                return new DependencySpec(FeaturePackConfig.getDefaultOriginName(fp.getSpec().getFPID().getLocation()), fp.getFPID(), spec.getSpec());
            }
        }
        for (FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            FeatureSpec spec = fp.getFeatureSpec(name);
            if (spec != null) {
                return new DependencySpec(FeaturePackConfig.getDefaultOriginName(fp.getSpec().getFPID().getLocation()), fp.getFPID(), spec);
            }
        }
        return null;
    }

    private static void resolveAddressParams(FeatureConfig featureConfig, Map<String, String> address, FeatureAnnotation annotation) {
        List<String> addressParams = annotation.getElementAsList(ADDR_PARAMS);
        List<String> addressParamMappings = annotation.getElementAsList(ADDR_PARAMS_MAPPING);
        if (addressParamMappings == null || addressParamMappings.isEmpty()) {
            addressParamMappings = addressParams;
        }
        for (int i = 0; i < addressParams.size(); i++) {
            String value = address.get(addressParams.get(i));
            if (value != null) {
                if ("undefined".equals(value)) {
                    value = GLN_UNDEFINED;
                }
            } else {
                value = GLN_UNDEFINED;
            }
            featureConfig.putParam(addressParamMappings.get(i), value);
        }
    }

    private static void resolveParams(FeatureConfig featureConfig, Map<String, String> params, FeatureAnnotation annotation) {
        List<String> addressParams = annotation.getElementAsList(OP_PARAMS);
        List<String> addressParamMappings = annotation.getElementAsList(OP_PARAMS_MAPPING);
        if (addressParamMappings == null || addressParamMappings.isEmpty()) {
            addressParamMappings = addressParams;
        }
        for (int i = 0; i < addressParams.size(); i++) {
            String value = params.get(addressParams.get(i));
            if(value == null) {
                continue;
            }
            featureConfig.putParam(addressParamMappings.get(i), value);
        }
    }

    private static class DependencySpec {

        private final String fpName;
        private final FPID fpid;
        private final FeatureSpec spec;

        DependencySpec(String fpName, FPID fpid, FeatureSpec spec) {
            this.fpName = fpName;
            this.spec = spec;
            this.fpid = fpid;
        }
    }
}
