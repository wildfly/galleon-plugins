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

package org.wildfly.galleon.plugin.config.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.diff.FsDiff;
import org.jboss.galleon.diff.FsEntry;
import org.jboss.galleon.diff.ProvisioningDiffProvider;
import org.jboss.galleon.layout.FeaturePackLayoutFactory;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.plugin.ProvisionedConfigHandler;
import org.jboss.galleon.runtime.FeaturePackRuntimeBuilder;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.runtime.ResolvedFeatureId;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.runtime.ResolvedSpecId;
import org.jboss.galleon.spec.FeaturePackSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.state.ProvisionedConfig;
import org.jboss.galleon.state.ProvisionedFeature;
import org.jboss.galleon.state.ProvisionedState;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.xml.ProvisionedConfigBuilder;
import org.jboss.galleon.xml.ProvisionedConfigXmlParser;
import org.jboss.galleon.xml.ProvisionedConfigXmlWriter;
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

    private static final String ADDED = "added";
    private static final String UPDATED = "updated";

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

    private interface FsEntryProvider {
        FsEntry getFsEntry(String relativePath);
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

    private static void processPaths(Path home, Iterable<String> relativePaths, FsEntryProvider fsEntries, Map<ConfigId, String> affectedConfigs) throws ProvisioningException {
        for(String relativePath : relativePaths) {
            if(!isWfConfig(relativePath)) {
                continue;
            }
            final String rootElement = getRootElement(home.resolve(relativePath));
            String model;
            if(rootElement.regionMatches(0, SERVER_ELEMENT, 0, SERVER_ELEMENT.length())) {
                model = WfConstants.STANDALONE;
            } else if(rootElement.regionMatches(0, DOMAIN_ELEMENT, 0, DOMAIN_ELEMENT.length())) {
                model = WfConstants.DOMAIN;
            } else if(rootElement.regionMatches(0, HOST_ELEMENT, 0, HOST_ELEMENT.length())) {
                model = WfConstants.HOST;
            } else {
                continue;
            }
            final FsEntry modifiedEntry = fsEntries.getFsEntry(relativePath);
            affectedConfigs.put(new ConfigId(model, modifiedEntry.getName()), modifiedEntry.getRelativePath());
        }
    }

    private static boolean isWfConfig(String relativePath) {
        if(!relativePath.endsWith(DOT_XML)) {
            return false;
        }
        int i;
        if(relativePath.startsWith(WfConstants.STANDALONE)) {
            i = WfConstants.STANDALONE.length();
        } else if(relativePath.startsWith(WfConstants.DOMAIN)) {
            i = WfConstants.DOMAIN.length();
        } else {
            return false;
        }
        if(relativePath.charAt(i) != '/') {
            return false;
        }
        if(!relativePath.regionMatches(++i, WfConstants.CONFIGURATION, 0, WfConstants.CONFIGURATION.length())) {
            return false;
        }
        i += WfConstants.CONFIGURATION.length();
        if(relativePath.charAt(i) != '/') {
            return false;
        }
        if(relativePath.indexOf('/', i + 1) > 0) {
            return false;
        }
        return true;
    }

    public static void exportDiff(ProvisioningDiffProvider diffProvider) throws ProvisioningException {
        final FsDiff fsDiff = diffProvider.getFsDiff();

        final Map<ConfigId, String> affectedConfigs = new LinkedHashMap<>(0);
        if(fsDiff.hasModifiedEntries()) {
            processPaths(fsDiff.getOtherRoot().getPath(), fsDiff.getModifiedPaths(), new FsEntryProvider() {
                @Override
                public FsEntry getFsEntry(String relativePath) {
                    return fsDiff.getModifiedEntry(relativePath)[0];
                }}, affectedConfigs);
        }
        if(fsDiff.hasAddedEntries()) {
            processPaths(fsDiff.getOtherRoot().getPath(), fsDiff.getAddedPaths(), new FsEntryProvider() {
                @Override
                public FsEntry getFsEntry(String relativePath) {
                    return fsDiff.getAddedEntry(relativePath);
                }}, affectedConfigs);
        }

        if(!affectedConfigs.isEmpty()) {
            final WfConfigsReader reader = new WfConfigsReader();
            reader.log = diffProvider.getMessageWriter();
            reader.home = fsDiff.getOtherRoot().getPath();
            reader.configIds = affectedConfigs;

            reader.generate(diffProvider.getProvisioningLayout(), diffProvider.getProvisionedState(), reader.home, reader.log, false);

            WfFeatureDiffCallback featureCallback = null;
            if (!reader.updatedConfigs.isEmpty()) {
                featureCallback = new WfFeatureDiffCallback();
                for (ProvisionedConfig config : reader.updatedConfigs) {
                    diffProvider.updateConfig(featureCallback, config,
                            reader.configIds.get(new ConfigId(config.getModel(), config.getName())));
                }
            }
            if (!reader.addedConfigs.isEmpty()) {
                if (featureCallback == null) {
                    featureCallback = new WfFeatureDiffCallback();
                }
                for (ProvisionedConfig config : reader.addedConfigs) {
                    diffProvider.addConfig(featureCallback, config,
                            reader.configIds.get(new ConfigId(config.getModel(), config.getName())));
                }
            }
        }
        if(fsDiff.hasRemovedEntries()) {
            for(String relativePath : fsDiff.getRemovedPaths()) {
                if(!isWfConfig(relativePath)) {
                    continue;
                }
                final FsEntry removed = fsDiff.getRemovedEntry(relativePath);
                final ConfigId configId;
                switch(relativePath.substring(0, relativePath.indexOf('/'))) {
                    case WfConstants.STANDALONE:
                        configId = new ConfigId(WfConstants.STANDALONE, removed.getName());
                        break;
                    case WfConstants.DOMAIN:
                        String model = null;
                        for(ProvisionedConfig provisioned : diffProvider.getProvisionedState().getConfigs()) {
                            if(provisioned.getName().equals(removed.getName())) {
                                if(model != null) {
                                    model = null;
                                    break;
                                }
                                model = provisioned.getModel();
                            }
                        }
                        if(model == null) {
                            continue;
                        }
                        configId = new ConfigId(model, removed.getName());
                        break;
                    default:
                        continue;
                }
                diffProvider.removeConfig(configId, relativePath);
            }
        }
    }

    private Path home;
    private MessageWriter log;
    private Map<ConfigId, String> configIds;
    private ProvisioningLayout<FeaturePackRuntimeBuilder> layout;
    private ProvisionedState provisionedState;
    private Map<String, FeatureSpec> loadedSpecs = Collections.emptyMap();
    private ConfigId configId;
    private List<ProvisionedConfig> updatedConfigs = Collections.emptyList();
    private List<ProvisionedConfig> addedConfigs = Collections.emptyList();
    @Override
    protected String getHome(ProvisioningRuntime runtime) {
        return home.toString();
    }

    @Override
    protected void doGenerate(ProvisioningLayout<FeaturePackRuntimeBuilder> layout, ProvisionedState provisionedState) throws ProvisioningException {
        this.layout = layout;
        this.provisionedState = provisionedState;

        for(Map.Entry<ConfigId, String> entry : configIds.entrySet()) {
            final Path configXml = home.resolve(entry.getValue());
            if (!Files.exists(configXml)) {
                throw new ProvisioningException("Config " + configId + " does not exist: " + configXml);
            }
            this.configId = entry.getKey();
            readConfig(getConfigArg(configId.getModel()), configXml);
        }
    }

    @Override
    protected String[] getForkArgs() throws ProvisioningException {
        final String[] superArgs = super.getForkArgs();
        int i = superArgs.length + 2;
        final String[] args = new String[i];
        System.arraycopy(superArgs, 0, args, 0, superArgs.length);
        final Path workDir = layout.getTmpPath("forked-wf-diff");
        final Path configXml = workDir.resolve("provisioning.xml");
        try {
            ProvisioningXmlWriter.getInstance().write(layout.getConfig(), configXml);
        } catch (Exception e) {
            throw new ProvisioningException("Failed to persist provisioning config", e);
        }
        args[--i] = configXml.toString();
        args[--i] = workDir.resolve("configs").toAbsolutePath().toString();
        return args;
    }

    @Override
    public void forkedForEmbedded(String... args) throws ProvisioningException {
        int i = args.length;
        final String provisioningXml = args[--i];
        final ProvisioningConfig provisioningConfig = ProvisioningXmlParser.parse(Paths.get(provisioningXml));
        layout = ProvisioningLayoutFactory.getInstance(null).newConfigLayout(provisioningConfig, new FeaturePackLayoutFactory<FeaturePackRuntimeBuilder>() {
            @Override
            public FeaturePackRuntimeBuilder newFeaturePack(FeaturePackLocation fpl, FeaturePackSpec spec, Path dir, int type)
                    throws ProvisioningException {
                return new FeaturePackRuntimeBuilder(fpl.getFPID(), null, dir, type);
            }}, false);
        super.forkedForEmbedded(args);
        --i;
        if(!addedConfigs.isEmpty()) {
            persistConfigs(args[i], ADDED, addedConfigs);
        }
        if(!updatedConfigs.isEmpty()) {
            persistConfigs(args[i], UPDATED, updatedConfigs);
        }
    }

    @Override
    public void forkedEmbeddedDone(String... args) throws ProvisioningException {
        final Path configsDir = Paths.get(args[args.length - 3]);
        if(!Files.exists(configsDir)) {
            return;
        }
        Path p = configsDir.resolve(ADDED);
        if(Files.exists(p)) {
            try(DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for(Path xml : stream) {
                    addedConfigs = CollectionUtils.add(addedConfigs, ProvisionedConfigXmlParser.parse(xml));
                }
            } catch (IOException e) {
                throw new ProvisioningException(Errors.readDirectory(p), e);
            }
        }
        p = configsDir.resolve(UPDATED);
        if(Files.exists(p)) {
            try(DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for(Path xml : stream) {
                    updatedConfigs = CollectionUtils.add(updatedConfigs, ProvisionedConfigXmlParser.parse(xml));
                }
            } catch (IOException e) {
                throw new ProvisioningException(Errors.readDirectory(p), e);
            }
        }
    }

    private static void persistConfigs(final String baseDir, String type, List<ProvisionedConfig> configs) throws ProvisioningException {
        final Path configsDir = Paths.get(baseDir).resolve(type);
        try {
            Files.createDirectories(configsDir);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.mkdirs(configsDir), e);
        }
        final ProvisionedConfigXmlWriter writer = ProvisionedConfigXmlWriter.getInstance();
        for(ProvisionedConfig config : configs) {
            try {
                writer.write(config, configsDir.resolve(config.getName()));
            } catch (Exception e) {
                throw new ProvisioningException(Errors.writeFile(configsDir.resolve(config.getName())), e);
            }
        }
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

        final int model;
        switch(configId.getModel()) {
            case WfConstants.STANDALONE:
                model = 0;
                break;
            case WfConstants.DOMAIN:
                model = 1;
                break;
            case WfConstants.HOST:
                model = 2;
                break;
            default:
                throw new IllegalStateException("Unexpected config model " + configId.getModel());
        }

        if(log != null) {
            log.verbose("Reading config %s", configId);
        }

        ProvisionedConfigBuilder configBuilder = null;
        String prevSpec = null;
        ResolvedSpecId specId = null;
        for(ModelNode featureNode : response.get("result").asList()) {
            String specName;
            try {
                specName = featureNode.get("spec").asString();
            } catch(Throwable t) {
                throw new ProvisioningException("Failed to process " + featureNode, t);
            }
            if (model == 1) {
                if (specName.startsWith("profile.")) {
                    specName = specName.substring("profile.".length());
                }
            }

            if(!specName.equals(prevSpec)) {
                specId = resolveSpec(specName);
                if (specId == null) {
                    if(model == 1 && specName.startsWith("domain.")) {
                        specId = resolveSpec(specName.substring("domain.".length()));
                        if(specId == null) {
                            throw new ProvisioningException("Failed to locate feature spec " + featureNode.get("spec").asString()
                                    + " in the installed feature-packs");
                        }
                    } else if(model == 2 && specName.startsWith("host.")) {
                            specId = resolveSpec(specName.substring("host.".length()));
                            if(specId == null) {
                                throw new ProvisioningException("Failed to locate feature spec " + featureNode.get("spec").asString()
                                        + " in the installed feature-packs");
                            }
                    } else {
                        throw new ProvisioningException("Failed to locate feature spec " + featureNode.get("spec").asString()
                                + " in the installed feature-packs");
                    }
                }
                prevSpec = specName;
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

            if(specName.equals("path") && READ_ONLY_PATHS.contains(actualFeatureId.getParams().get("path"))) {
                continue;
            }

            final List<Property> params = featureNode.hasDefined("params") ? featureNode.get("params").asPropertyList() : Collections.emptyList();

            final ProvisionedFeatureBuilder featureBuilder = actualFeatureId == null ? ProvisionedFeatureBuilder.builder(specId) : ProvisionedFeatureBuilder.builder(actualFeatureId);
            final FeatureSpec featureSpec = getFeatureSpec(specId);
            for (Property param : params) {
                final String paramName = param.getName();
                if (!featureSpec.hasParam(paramName)) {
                    if(log != null) {
                        log.print("WARN: parameter " + paramName + " is not found in " + specId);
                    }
                    continue;
                }
                if (paramName.equals("module") && specName.equals("extension")
                        && param.getValue().equals(actualFeatureId.getParams().get("extension"))) {
                    continue;
                }
                featureBuilder.setConfigParam(param.getName(), param.getValue().asString());
            }

            if(configBuilder == null) {
                configBuilder = ProvisionedConfigBuilder.builder().setModel(configId.getModel()).setName(configId.getName());
            }
            configBuilder.addFeature(featureBuilder.build());
        }
        if(configBuilder != null) {
            boolean existing = false;
            for(ProvisionedConfig config : provisionedState.getConfigs()) {
                if((config.getModel() == null || config.getModel().equals(configId.getModel())) &&
                        (config.getName() == null || config.getName().equals(configId.getName()))) {
                    existing = true;
                    break;
                }
            }
            if(existing) {
                updatedConfigs = CollectionUtils.add(updatedConfigs, configBuilder.build());
            } else {
                addedConfigs = CollectionUtils.add(addedConfigs, configBuilder.build());
            }
        }
    }

    private ResolvedSpecId resolveSpec(final String specName) throws ProvisioningException {
        final List<FeaturePackRuntimeBuilder> fps = (List<FeaturePackRuntimeBuilder>) layout.getOrderedFeaturePacks();
        for(int i = fps.size() - 1; i >= 0; i--) {
            final FeaturePackRuntimeBuilder fp = fps.get(i);
            final ResolvedFeatureSpec spec = fp.getFeatureSpec(specName);
            if(spec != null) {
                return spec.getId();
            }
        }
        return null;
    }

    private FeatureSpec getFeatureSpec(ResolvedSpecId specId) throws ProvisioningException {
        FeatureSpec featureSpec = loadedSpecs.get(specId.getName());
        if(featureSpec != null) {
            return featureSpec;
        }
        featureSpec = layout.getFeaturePack(specId.getProducer()).getFeatureSpec(specId.getName()).getSpec();
        loadedSpecs = CollectionUtils.put(loadedSpecs, specId.getName(), featureSpec);
        return featureSpec;
    }

    private static String getRootElement(Path configPath) throws ProvisioningException {
        try(BufferedReader reader = Files.newBufferedReader(configPath)) {
            String line = reader.readLine();
            if(line == null) {
                return null;
            }
            do {
                line = line.trim();
                if(!line.isEmpty() && line.charAt(0) == '<') {
                    if(line.length() < 2) {
                        return null;
                    }
                    final char c = line.charAt(1);
                    if(c != '?' && c != '!') {
                        return line;
                    }
                }
                line = reader.readLine();
            } while(line != null);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(configPath), e);
        }
        return null;
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
        if(configId == null) {
            configId = new ConfigId(WfConstants.STANDALONE, getArg(WfConstants.EMBEDDED_ARG_SERVER_CONFIG, "standalone.xml", args));
        }
        super.doStartServer(args);
    }

    @Override
    protected void doStartHc(String... args) throws ProvisioningException {
        if(configId == null) {
            int i = 0;
            while(i < args.length) {
                final String argValue = args[i++];
                if(argValue.equals(WfConstants.EMBEDDED_ARG_HOST_CONFIG)) {
                    configId = new ConfigId(WfConstants.HOST, args[i]);
                    break;
                } else if(argValue.equals(WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG)) {
                    configId = new ConfigId(WfConstants.DOMAIN, args[i]);
                    // no break, domain is the default
                }
            }
        }
        super.doStartHc(args);
    }

    void stopEmbedded() throws ProvisioningException {
        super.stopEmbedded();
        configId = null;
    }

    private static String getArg(final String argName, final String defValue, String... args) {
        int i = 0;
        while(i < args.length) {
            if(argName.equals(args[i++])) {
                return args[i];
            }
        }
        return defValue;
    }
}
