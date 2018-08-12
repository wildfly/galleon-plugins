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

package org.wildfly.galleon.plugin.config.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.arguments.ArgumentValueCallbackHandler;
import org.jboss.as.cli.parsing.arguments.ArgumentValueInitialState;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.FeatureConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.plugin.ProvisionedConfigHandler;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.runtime.ResolvedFeatureId;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.runtime.ResolvedSpecId;
import org.jboss.galleon.spec.FeatureId;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.state.ProvisionedConfig;
import org.jboss.galleon.state.ProvisionedFeature;
import org.jboss.galleon.state.ProvisionedState;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.xml.ConfigXmlWriter;
import org.jboss.galleon.xml.ProvisionedFeatureBuilder;
import org.jboss.galleon.xml.ProvisioningXmlParser;
import org.jboss.galleon.xml.ProvisioningXmlWriter;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexay Loubyansky
 */
public class WfConfigsReader extends WfEmbeddedTaskBase<List<ProvisionedConfig>> {

    private static final String DOMAIN_ELEMENT = "<domain ";
    private static final String HOST_ELEMENT = "<host ";
    private static final String SERVER_ELEMENT = "<server ";

    public static class ConfigSpecMapper implements ProvisionedConfigHandler {

        Map<String, Map<ResolvedFeatureId, ProvisionedFeature>> features = new HashMap<>();
        Map<String, ResolvedSpecId> specs = new HashMap<>();
        Set<String> excludedSpecs = Collections.emptySet();
        private String specName;
        private Map<ResolvedFeatureId, ProvisionedFeature> specFeatures;

        public void reset() {
            features.clear();
            specs.clear();
            excludedSpecs = Collections.emptySet();
            specName = null;
            specFeatures = null;
        }

        public void map(ProvisionedConfig config) throws ProvisioningException {
            config.handle(this);
            specName = null;
            specFeatures = null;
        }

        @Override
        public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
            specName = spec.getName();
            specFeatures = features.get(specName);
            if(specFeatures == null) {
                specFeatures = new LinkedHashMap<>();
                features.put(specName, specFeatures);
            }
            specs.put(specName, spec.getId());
        }

        @Override
        public void nextFeature(ProvisionedFeature feature) throws ProvisioningException {
            if(feature.getId() == null) {
                excludedSpecs = CollectionUtils.addLinked(excludedSpecs, feature.getSpecId().getName());
                return;
            }
            specFeatures.put(feature.getId(), feature);
        }
    }

    private static final String DOT_XML = ".xml";
    private static final Set<String> READ_ONLY_PATHS;

    static {
        final Set<String> tmp = new HashSet<>(10);
        tmp.add("java.home");
        tmp.add("jboss.home.dir");
        tmp.add("jboss.controller.temp.dir");
        tmp.add("jboss.server.temp.dir");
        tmp.add("user.home");
        tmp.add("user.dir");
        tmp.add("jboss.server.config.dir");
        tmp.add("jboss.server.base.dir");
        tmp.add("jboss.server.data.dir");
        tmp.add("jboss.server.log.dir");
        READ_ONLY_PATHS = Collections.unmodifiableSet(tmp);
    }

    public static List<ConfigModel> exportDiff(ProvisioningLayout<?> layout, ProvisionedState provisionedState, MessageWriter log, Path home) throws ProvisioningException {
        final WfConfigsReader reader = new WfConfigsReader();
        reader.log = log;
        reader.home = home;
        reader.generate(layout, provisionedState, home, log, false);

        Path baseDir = Paths.get(System.getProperty("user.home")).resolve("galleon-scripts");
        for(ConfigModel config : reader.userConfigs) {
            try {
                ConfigXmlWriter.getInstance().write(config, baseDir.resolve(config.getName()));
            } catch (XMLStreamException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return reader.userConfigs;
    }

    private Path home;
    private MessageWriter log;
    private ProvisioningLayout<?> layout;
    private ProvisionedConfig provisionedConfig;
    private ConfigSpecMapper specMapper = new ConfigSpecMapper();
    private Map<String, FeatureSpec> loadedSpecs = Collections.emptyMap();
    private List<ConfigModel> userConfigs = Collections.emptyList();
    private String configModel;
    private String configName;

    @Override
    protected String getHome(ProvisioningRuntime runtime) {
        return home.toString();
    }

    @Override
    protected void doGenerate(ProvisioningLayout<?> layout, ProvisionedState provisionedState) throws ProvisioningException {
        this.layout = layout;
        final List<ProvisionedConfig> provisionedConfigs = provisionedState.getConfigs();
        Map<String, Path> actualStandaloneConfigs = new HashMap<>(provisionedConfigs.size());
        Path configDir = home.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION);
        if(Files.exists(configDir)) {
            locateConfigs(configDir, actualStandaloneConfigs, SERVER_ELEMENT);
        }
        configDir = home.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION);
        if(Files.exists(configDir)) {
            locateConfigs(configDir, actualStandaloneConfigs, DOMAIN_ELEMENT, HOST_ELEMENT);
        }

        for(ProvisionedConfig config : provisionedConfigs) {
            final Path path = actualStandaloneConfigs.remove(config.getName());
            if(path == null) {
                // TODO exclude the config
                System.out.println("Exclude config " + config.getName());
                continue;
            }
            this.provisionedConfig = config;
            this.configModel = config.getModel();
            this.configName = config.getName();
            readConfig(getConfigArg(configModel), path);
            this.provisionedConfig = null;
        }
        configModel = WfConstants.STANDALONE;
        for(Path newConfig : actualStandaloneConfigs.values()) {
            configModel = newConfig.getParent().getParent().getFileName().toString();
            configName = newConfig.getFileName().toString();
            readConfig(getConfigArg(configModel), newConfig);
        }
    }

    @Override
    protected String[] getForkArgs() throws ProvisioningException {
        final String[] superArgs = super.getForkArgs();
        int i = superArgs.length + 2;
        final String[] args = new String[i];
        System.arraycopy(superArgs, 0, args, 0, superArgs.length);
        final Path configXml = layout.getTmpPath("diffgen").resolve("provisioning.xml");
        try {
            ProvisioningXmlWriter.getInstance().write(layout.getConfig(), configXml);
        } catch (Exception e) {
            throw new ProvisioningException("Failed to persist provisioning config", e);
        }
        args[--i] = configXml.toString();
        args[--i] = layout.getFactory().getHome().toString();
        return args;
    }

    @Override
    public void forkedForEmbedded(String... args) throws ProvisioningException {
        int i = args.length;
        final String provisioningXml = args[--i];
        final ProvisioningConfig provisioningConfig = ProvisioningXmlParser.parse(Paths.get(provisioningXml));
        final Path layoutFactoryHome = Paths.get(args[--i]);
        layout = ProvisioningLayoutFactory.getInstance(layoutFactoryHome, null).newConfigLayout(provisioningConfig);
        super.forkedForEmbedded(args);
    }

    private String getConfigArg(final String configModel) {
        switch(configModel) {
            case WfConstants.STANDALONE:
                return WfConstants.EMBEDDED_ARG_SERVER_CONFIG;
            case WfConstants.DOMAIN:
                return WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG;
            case WfConstants.HOST:
                return WfConstants.EMBEDDED_ARG_HOST_CONFIG;
            default:
                throw new IllegalStateException("Unexpected config model " + configModel);
        }
    }

    private void locateConfigs(Path configDir, Map<String, Path> actualConfigs, String... firstElement) throws ProvisioningException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for(Path p : stream) {
                if(!Files.isRegularFile(p)) {
                    continue;
                }
                final String fileName = p.getFileName().toString();
                final int length = fileName.length();
                if(length < DOT_XML.length() + 1 || !fileName.regionMatches(true, length - DOT_XML.length(), DOT_XML, 0, DOT_XML.length())) {
                    continue;
                }
                if(!isConfig(p, firstElement)) {
                    continue;
                }
                actualConfigs.put(fileName, p);
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readDirectory(configDir), e);
        }
    }

    private String hostName;

    private void readConfig(String configArg, Path configPath) throws ProvisioningException {
        try {
            final ModelNode readConfigOp = Operations.createOperation("read-config-as-features");
            if (configArg.equals(WfConstants.EMBEDDED_ARG_SERVER_CONFIG)) {
                startServer("--admin-only", configArg, configPath.getFileName().toString());
            } else {
                startHc(configArg, configPath.getFileName().toString());
                if (configArg.equals(WfConstants.EMBEDDED_ARG_HOST_CONFIG)) {
                    hostName = "";
                    final ModelNode readHostNameOp = Operations.createOperation(ClientConstants.READ_CHILDREN_NAMES_OPERATION);
                    readHostNameOp.get(ClientConstants.CHILD_TYPE).set(ClientConstants.HOST);
                    handle(readHostNameOp);
                    final ModelNode addr = readConfigOp.get(ClientConstants.OP_ADDR);
                    addr.add(ClientConstants.HOST, hostName);
                    hostName = null;
                }
            }
            readConfigOp.get("nested").set(false);
            handle(readConfigOp);
        } finally {
            stopEmbedded();
        }
    }

    @Override
    protected void handleSuccess(ModelNode response) throws ProvisioningException {

        if(hostName != null) {
            if(!Operations.isSuccessfulOutcome(response)) {
                throw new ProvisioningException("Failed to determine the host name: " + Operations.getFailureDescription(response));
            }
            final List<ModelNode> list = Operations.readResult(response).asList();
            if(list.size() != 1) {
                throw new ProvisioningException("Failed to determine the host name: expected one item in the list but got " + list);
            }
            hostName = list.get(0).asString();
            return;
        }

        specMapper.reset();
        if(provisionedConfig != null) {
            specMapper.map(provisionedConfig);
        }

        final int model;
        if(configModel.equals(WfConstants.STANDALONE)) {
            model = 0;
        } else if(configModel.equals(WfConstants.DOMAIN)) {
            model = 1;
        } else if(configModel.equals(WfConstants.HOST)) {
            model = 2;
        } else {
            throw new IllegalStateException("Unexpected config model " + configModel);
        }

        if(log != null) {
            log.print("Analyzing " + configName);
        }

        ConfigModel.Builder configBuilder = null;
        String prevSpec = null;
        ResolvedSpecId specId = null;
        Map<ResolvedFeatureId, ProvisionedFeature> specFeatures = Collections.emptyMap();
        for(ModelNode featureNode : response.get("result").asList()) {
            String specName;
            try {
                specName = featureNode.get("spec").asString();
            } catch(Throwable t) {
                throw new ProvisioningException("Failed to process " + featureNode, t);
            }
            if (model == 1 && specName.startsWith("profile.")) {
                specName = specName.substring("profile.".length());
            }

            if(!specName.equals(prevSpec)) {
                specId = specMapper.specs.get(specName);
                if(specId == null) {
                    specId = resolveSpec(specName);
                    if(specId == null) {
                        if(model == 1 && specName.startsWith("domain.")) {
                            specName = specName.substring("domain.".length());
                            specId = resolveSpec(specName);
                        } else if(model == 2 && specName.startsWith("host.")) {
                            specName = specName.substring("host.".length());
                            specId = resolveSpec(specName);
                        }
                    }
                    if(specId == null) {
                        throw new ProvisioningException("Failed to locate feature spec " + featureNode.get("spec").asString() + " in the installed feature-packs");
                    }
                }
                prevSpec = specName;
                specFeatures = specMapper.features.get(specName);
            }

            ResolvedFeatureId actualFeatureId = null;
            if(featureNode.hasDefined("id")) {
                final List<Property> props = featureNode.get("id").asPropertyList();
                if(!props.isEmpty()) {
                    final ResolvedFeatureId.Builder idBuilder = ResolvedFeatureId.builder(specId);
                    for (Property prop : props) {
                        idBuilder.setParam(prop.getName(), prop.getValue().asString());
                    }
                    actualFeatureId = idBuilder.build();
                }
            }

            final ProvisionedFeature provisionedFeature = specFeatures == null ? null : specFeatures.remove(actualFeatureId);

            if(!featureNode.hasDefined("params")) {
                continue;
            }
            final List<Property> params = featureNode.get("params").asPropertyList();
            if (params.isEmpty()) {
                continue;
            }

            if(provisionedFeature == null) {
                if(specName.equals("path") && READ_ONLY_PATHS.contains(actualFeatureId.getParams().get("path"))) {
                    continue;
                }
                final FeatureConfig feature = actualFeatureId == null ? new FeatureConfig(specName) : newFeatureConfig(actualFeatureId);
                for (Property prop : params) {
                    feature.setParam(prop.getName(), prop.getValue().asString());
                }
                if(configBuilder == null) {
                    configBuilder = ConfigModel.builder(configModel, configName);
                }
                configBuilder.addFeature(feature);
                if(log != null) {
                    log.print("Added feature %s", feature);
                }
                continue;
            }

            FeatureConfig feature = null;
            final Map<String, String> provisionedParams = new HashMap<>(((ProvisionedFeatureBuilder) provisionedFeature).getConfigParams());
            for (String idParam : actualFeatureId.getParams().keySet()) {
                provisionedParams.remove(idParam);
            }
            final FeatureSpec featureSpec = getFeatureSpec(specId);
            for (Property param : params) {
                final String paramName = param.getName();
                if (!featureSpec.hasParam(paramName)) {
                    if(log != null) {
                        log.print("WARN: parameter " + paramName + " is not found in " + specId);
                    }
                    continue;
                }
                final FeatureParameterSpec paramSpec = featureSpec.getParam(paramName);
                final Object provisionedValue = resolve(param, provisionedParams.remove(paramName));

                final Object actualValue = toJava(param.getValue());
                if (provisionedValue != null && actualValue.equals(provisionedValue)) {
                    continue;
                }
                if (provisionedValue == null) {
                    if (paramName.equals("module") && specName.equals("extension")
                            && actualValue.equals(actualFeatureId.getParams().get("extension"))) {
                        continue;
                    }
                    if (feature == null) {
                        feature = actualFeatureId == null ? new FeatureConfig(specName) : newFeatureConfig(actualFeatureId);
                    }
                    feature.setParam(paramName, param.getValue().asString());
                    if(log != null) {
                        log.print("Parameter %s of %s set to %s", paramName, actualFeatureId, actualValue);
                    }
                    continue;
                }

                if(log != null) {
                    log.print("Parameter %s of %s changed from %s to %s", paramName, actualFeatureId, provisionedValue, actualValue);
                }

                if (!provisionedValue.equals(resolve(param, paramSpec.getDefaultValue()))) {
                    if (feature == null) {
                        feature = actualFeatureId == null ? new FeatureConfig(specName) : newFeatureConfig(actualFeatureId);
                    }
                    feature.setParam(paramName, param.getValue().asString());
                }
            }
            if (!provisionedParams.isEmpty()) {
                for (Map.Entry<String, String> entry : provisionedParams.entrySet()) {
                    final String paramName = entry.getKey();
                    final FeatureParameterSpec paramSpec = featureSpec.getParam(paramName);
                    if (Constants.GLN_UNDEFINED.equals(paramSpec.getDefaultValue())
                            || paramName.equals("extension") && specName.startsWith("subsystem.")
                            || paramName.equals("persist-name") && specName.equals("host")) {
                        continue;
                    }
                    if (feature == null) {
                        feature = actualFeatureId == null ? new FeatureConfig(specName) : newFeatureConfig(actualFeatureId);
                    }
                    feature.unsetParam(paramName);
                    if(log != null) {
                        log.print("Parameter %s of %s is unset", paramName, actualFeatureId);
                    }
                }
            }
            if (feature != null) {
                if(configBuilder == null) {
                    configBuilder = ConfigModel.builder(configModel, configName);
                }
                configBuilder.addFeature(feature);
            }
        }

        if(!specMapper.features.isEmpty()) {
            for(Map<ResolvedFeatureId, ProvisionedFeature> removed : specMapper.features.values()) {
                if(removed.isEmpty()) {
                    continue;
                }
                for(Map.Entry<ResolvedFeatureId, ProvisionedFeature> entry : removed.entrySet()) {
                    final ResolvedFeatureId removedId = entry.getKey();
                    if(removedId == null) {
                        throw new ProvisioningException("Feature " + entry.getValue() + " has no ID");
                    }
                    final String specName = removedId.getSpecId().getName();
                    if(specName.equals("core-service.management") ||
                            specName.equals("server-root")) {
                        continue;
                    }
                    if(configBuilder == null) {
                        configBuilder = ConfigModel.builder(configModel, configName);
                    }
                    final FeatureId.Builder idBuilder = FeatureId.builder(specName);
                    for(Map.Entry<String, Object> entry2 : removedId.getParams().entrySet()) {
                        idBuilder.setParam(entry2.getKey(), entry2.getValue().toString());
                    }
                    configBuilder.excludeFeature(idBuilder.build());
                    if(log != null) {
                        log.print("Excluded %s", removedId);
                    }
                }
            }
        }
        if(!specMapper.excludedSpecs.isEmpty()) {
            if(configBuilder == null) {
                configBuilder = ConfigModel.builder(configModel, configName);
            }
            for(String excludedSpec : specMapper.excludedSpecs) {
                configBuilder.excludeSpec(excludedSpec);
            }
        }
        if(configBuilder != null) {
            userConfigs = CollectionUtils.add(userConfigs, configBuilder.build());
        }
    }

    private ResolvedSpecId resolveSpec(final String specName) throws ProvisioningException {
        @SuppressWarnings("unchecked")
        final List<FeaturePackLayout> fps = (List<FeaturePackLayout>) layout.getOrderedFeaturePacks();
        for(int i = fps.size() - 1; i >= 0; i--) {
            final FeaturePackLayout fp = fps.get(i);
            final FeatureSpec spec = fp.loadFeatureSpec(specName);
            if(spec != null) {
                return new ResolvedSpecId(fp.getFPID().getProducer(), specName);
            }
        }
        return null;
    }

    private FeatureSpec getFeatureSpec(ResolvedSpecId specId) throws ProvisioningException {
        FeatureSpec featureSpec = loadedSpecs.get(specId.getName());
        if(featureSpec != null) {
            return featureSpec;
        }
        featureSpec = layout.getFeaturePack(specId.getProducer()).loadFeatureSpec(specId.getName());
        loadedSpecs = CollectionUtils.put(loadedSpecs, specId.getName(), featureSpec);
        return featureSpec;
    }

    private FeatureConfig newFeatureConfig(ResolvedFeatureId id) throws ProvisioningDescriptionException {
        FeatureConfig featureConfig = new FeatureConfig(id.getSpecId().getName());
        for(Map.Entry<String, Object> param : id.getParams().entrySet()) {
            featureConfig.setParam(param.getKey(), param.getValue().toString());
        }
        return featureConfig;
    }

    private static boolean isConfig(Path configPath, String... firstElement) throws ProvisioningException {
        try(BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line = reader.readLine();
            if(line == null) {
                return false;
            }
            do {
                line = line.trim();
                if(!line.isEmpty() && line.charAt(0) == '<') {
                    if(line.length() < 2) {
                        return false;
                    }
                    final char c = line.charAt(1);
                    if(c != '?' && c != '!') {
                        for(String e : firstElement) {
                            if(line.regionMatches(0, e, 0, e.length())) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                line = reader.readLine();
            } while(line != null);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(configPath), e);
        }
        return false;
    }

    @Override
    public void forkedEmbeddedMessage(String msg) {
        //System.out.println(msg);
    }

    public List<ProvisionedConfig> getResult() {
        return Collections.emptyList();
    }

    @Override
    protected void doStartServer(String... args) throws ProvisioningException {
        if(configModel == null) {
            configModel = WfConstants.STANDALONE;
        }
        super.doStartServer(args);
    }

    @Override
    protected void doStartHc(String... args) throws ProvisioningException {
        if(configModel == null) {
            configModel = WfConstants.DOMAIN;
            for(String arg : args) {
                if(arg.startsWith(WfConstants.EMBEDDED_ARG_HOST_CONFIG)) {
                    configModel = WfConstants.HOST;
                    break;
                }
            }
        }
        super.doStartHc(args);
    }

    /**
     * The below methods are necessary to properly compare complex attribute values
     */

    private static Object resolve(Property prop, final String provisionedValue) throws ProvisioningException {
        if(provisionedValue == null ||
                provisionedValue.isEmpty() ||
                provisionedValue.length() > 2 && provisionedValue.charAt(0) == '$' && provisionedValue.charAt(1) == '{') {
            return provisionedValue;
        }
        return toJava(toDmr(prop, provisionedValue));
    }

    private static ModelNode toDmr(Property prop, final String provisionedValue) throws ProvisioningException {
        try {
            return ModelNode.fromString(provisionedValue);
        } catch (Exception e) {
            final ArgumentValueCallbackHandler handler = new ArgumentValueCallbackHandler();
            try {
                StateParser.parse(provisionedValue, handler, ArgumentValueInitialState.INSTANCE);
            } catch (CommandFormatException e1) {
                throw new ProvisioningException("Failed to parse parameter " + prop.getName() + " '" + provisionedValue + "'", e1);
            }
            return handler.getResult();
        }
    }

    private static final Object EMPTY_LIST_OR_OBJ = new Object();

    private static Object toJava(ModelNode node) {
        switch(node.getType()) {
            case LIST: {
                final List<ModelNode> list = node.asList();
                if(list.isEmpty()) {
                    return EMPTY_LIST_OR_OBJ;
                }
                final int size = list.size();
                if(size == 1) {
                    return Collections.singletonList(toJava(list.get(0)));
                }
                final List<Object> o = new ArrayList<>(size);
                for(ModelNode item : list) {
                    o.add(toJava(item));
                }
                return o;
            }
            case OBJECT: {
                final List<Property> list = node.asPropertyList();
                if(list.isEmpty()) {
                    return EMPTY_LIST_OR_OBJ;
                }
                final int size = list.size();
                if(size == 1) {
                    final Property prop = list.get(0);
                    return Collections.singletonMap(prop.getName(), toJava(prop.getValue()));
                }
                Map<String, Object> map = new HashMap<>(size);
                for (Property prop : list) {
                    map.put(prop.getName(), toJava(prop.getValue()));
                }
                return map;
            }
            case PROPERTY: {
                final Property prop = node.asProperty();
                return Collections.singletonMap(prop.getName(), toJava(prop.getValue()));
            }
            default: {
                return node.asString();
            }
        }
    }
}
