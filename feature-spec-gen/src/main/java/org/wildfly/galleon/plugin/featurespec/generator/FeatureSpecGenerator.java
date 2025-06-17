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
package org.wildfly.galleon.plugin.featurespec.generator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.dmr.Property;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.StringUtils;
import org.jboss.galleon.xml.FeatureSpecXmlParser;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ForkCallback;
import org.wildfly.galleon.plugin.server.ForkedEmbeddedUtil;
import org.wildfly.galleon.plugin.server.ConfigGeneratorException;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureSpecGenerator implements ForkCallback {

    private Map<String, FeatureSpecNode> nodesBySpecName = new HashMap<>();
    private Map<String, Map<String, FeatureSpecNode>> referencedSpecs = new HashMap<>();
    private Map<String, FeatureSpecNode> capProviders = new HashMap<>();
    private int specsGenerated;

    private String installation;
    Path outputDir;
    private boolean fork;
    private boolean debug;
    private Map<String, Path> inheritedSpecs;
    private Map<String, FeatureSpec> parsedInheritedSpecs = Collections.emptyMap();

    private Path systemProps;
    private Path standaloneSpecsFile;
    private Path domainSpecsFile;
    private String mimimumStability;

    String getBranchId(String spec, int dots) {
        int i = 0;
        int index = 0;
        while (i <= dots) {
            index = spec.indexOf('.', index + 1);
            if (index < 0) {
                return spec;
            }
            ++i;
        }
        return spec.substring(0, index);
    }

    void addSpec(String name, FeatureSpecNode node) {
        nodesBySpecName.put(name, node);
    }

    FeatureSpecNode getSpec(String name) {
        return nodesBySpecName.get(name);
    }

    void addReferencedSpec(String referencedSpecName, String referencingSpec, FeatureSpecNode referencingNode) {
        Map<String, FeatureSpecNode> specs = referencedSpecs.get(referencedSpecName);
        if (specs != null) {
            if (specs.size() == 1) {
                specs = new HashMap<>(specs);
                referencedSpecs.put(referencedSpecName, specs);
            }
            specs.put(referencingSpec, referencingNode);
        } else {
            referencedSpecs.put(referencedSpecName, Collections.singletonMap(referencingSpec, referencingNode));
        }
    }

    Map<String, FeatureSpecNode> getReferencingSpecs(String referencedSpecName) {
        final Map<String, FeatureSpecNode> refs = referencedSpecs.get(referencedSpecName);
        return refs == null ? Collections.emptyMap() : refs;
    }

    void clearReferencingSpecs(String referencedSpecName) {
        referencedSpecs.remove(referencedSpecName);
    }

    void addCapProvider(String cap, FeatureSpecNode spec) {
        capProviders.put(cap, spec);
    }

    FeatureSpecNode getCapProvider(String cap) {
        return capProviders.get(cap);
    }

    boolean isInherited(String name) {
        return inheritedSpecs.containsKey(name);
    }

    void increaseSpecCount() {
        ++specsGenerated;
    }

    /**
     * This ctor has to be called only by reflection
     */
    public FeatureSpecGenerator() {
    }

    public FeatureSpecGenerator(String installation, Path outputDir, Map<String, Path> inheritedSpecs,
            String mimimumStability, boolean fork, boolean debug) {
        this.installation = installation;
        this.outputDir = outputDir;
        this.fork = fork;
        this.debug = debug;
        this.inheritedSpecs = inheritedSpecs;
        this.mimimumStability = mimimumStability;
    }

    public int generateSpecs() throws ProvisioningException {
        final Map<Object, Object> originalProps = new HashMap<>(System.getProperties());
        try {
            doGenerate();
        } finally {
            final List<String> toClear = new ArrayList<>();
            for (Map.Entry<Object, Object> prop : System.getProperties().entrySet()) {
                final Object value = originalProps.get(prop.getKey());
                if (value != null) {
                    System.setProperty(prop.getKey().toString(), value.toString());
                } else {
                    toClear.add(prop.getKey().toString());
                }
            }
            for (String prop : toClear) {
                System.clearProperty(prop);
            }
            if (systemProps != null) {
                IoUtils.recursiveDelete(systemProps);
            }
            if (standaloneSpecsFile != null) {
                IoUtils.recursiveDelete(standaloneSpecsFile);
            }
            if (domainSpecsFile != null) {
                IoUtils.recursiveDelete(domainSpecsFile);
            }
        }
        return specsGenerated;
    }

    private void doGenerate() throws ProvisioningException {
        final ModelNode standaloneFeatures;
        ModelNode domainRoots = null;
        if (fork) {
            String minStab = mimimumStability == null ? "" : mimimumStability;
            ForkedEmbeddedUtil.fork(this, debug, getStoredSystemProps(), installation, getStandaloneSpecsFile().toString(), getDomainSpecsFile().toString(), minStab);
            standaloneFeatures = readSpecsFile(getStandaloneSpecsFile());
            Path model = getStandaloneSpecsFile().getParent().resolve("model.json");
            try {
                Files.createDirectories(outputDir);
                Files.copy(model, outputDir.resolve("model.json"));
            } catch (IOException ex) {
                throw new ProvisioningException(ex);
            }
            if (Files.exists(Paths.get(installation).resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION))) {
                domainRoots = readSpecsFile(getDomainSpecsFile());
            }
        } else {
            final Path home = Paths.get(installation);
            if (Files.exists(home.resolve(WfConstants.STANDALONE).resolve(WfConstants.CONFIGURATION))) {
                standaloneFeatures = readFeatureSpecs(createStandaloneServer(installation, mimimumStability, null));
                Boolean all = Boolean.getBoolean("org.wildfly.galleon.complete.model");
                ModelNode result = generateModel(createStandaloneServer(installation, mimimumStability, all ? "standalone.xml" : "standalone-local.xml"), all);
                try {
                    if (!Files.exists(outputDir)) {
                        Files.createDirectories(outputDir);
                    }
                    Files.write(outputDir.resolve("model.json"), result.toJSONString(false).getBytes());
                } catch (IOException ex) {
                    throw new ProvisioningException(ex);
                }
            } else {
                throw new ProvisioningException("The installation does not include standalone configuration");
            }
            if (Files.exists(home.resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION))) {
                domainRoots = readFeatureSpecs(createEmbeddedHc(installation, mimimumStability));
            }
        }

        final FeatureSpecNode rootNode = new FeatureSpecNode(this, FeatureSpecNode.STANDALONE_MODEL, standaloneFeatures.require(ClientConstants.NAME).asString(), standaloneFeatures);

        if (domainRoots != null) {
            rootNode.setDomainDescr(WfConstants.DOMAIN, new ModelNode());
            rootNode.generateDomain = false;
            for (Property child : domainRoots.get("children").asPropertyList()) {
                final String specName = child.getName();
                if (specName.equals(WfConstants.HOST)) {
                    rootNode.setHostDescr(specName, child.getValue());
                } else if (specName.equals(WfConstants.PROFILE)) {
                    rootNode.setProfileDescr(specName, child.getValue());
                } else {
                    rootNode.domainDescr.get("children").add(specName, child.getValue());
                }
            }
        }

        rootNode.processChildren(FeatureSpecNode.STANDALONE_MODEL);
        if (domainRoots != null) {
            rootNode.processChildren(FeatureSpecNode.PROFILE_MODEL);
            rootNode.processChildren(FeatureSpecNode.DOMAIN_MODEL);
            rootNode.processChildren(FeatureSpecNode.HOST_MODEL);
        }

        rootNode.buildSpecs();
    }

    @Override
    public void forkedForEmbedded(String... args) throws ConfigGeneratorException {
        if (args.length != 3 && args.length != 4) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, Arrays.asList(args));
            throw new IllegalArgumentException("Expected 3-4 arguments but got " + Arrays.asList(args));
        }
        try {
            String mimimumStability = args.length == 4 ? args[3] : null;
            ModelNode result = readFeatureSpecs(createStandaloneServer(args[0], mimimumStability, null));
            writeSpecsFile(Paths.get(args[1]), result);
            Boolean all = Boolean.getBoolean("org.wildfly.galleon.complete.model");
            ModelNode resultModel = generateModel(createStandaloneServer(args[0], mimimumStability, all ? "standalone.xml" : "standalone-local.xml"), all);
            writeModelFile(Paths.get(args[1]).toAbsolutePath().getParent().resolve("model.json"), resultModel);
            System.out.println("FORKED TO " + Paths.get(args[1]).toAbsolutePath().getParent().resolve("model.json"));
            if (Files.exists(Paths.get(args[0]).resolve(WfConstants.DOMAIN).resolve(WfConstants.CONFIGURATION))) {
                result = readFeatureSpecs(createEmbeddedHc(args[0], mimimumStability));
                writeSpecsFile(Paths.get(args[2]), result);
            }
        } catch (ProvisioningException e) {
            throw new ConfigGeneratorException(e);
        }
    }

    @Override
    public void forkedEmbeddedMessage(String msg) {
        if (debug) {
            System.out.println(msg);
        }
    }

    protected Path getStoredSystemProps() throws ProvisioningException {
        if (systemProps == null) {
            systemProps = ForkedEmbeddedUtil.storeSystemProps();
        }
        return systemProps;
    }

    protected Path getStandaloneSpecsFile() throws ProvisioningException {
        if (standaloneSpecsFile == null) {
            try {
                standaloneSpecsFile = Files.createTempFile("wfgp", "standalone-specs");
            } catch (IOException e) {
                throw new ProvisioningException("Failed to create a tmp file", e);
            }
        }
        return standaloneSpecsFile;
    }

    protected Path getDomainSpecsFile() throws ProvisioningException {
        if (domainSpecsFile == null) {
            try {
                domainSpecsFile = Files.createTempFile("wfgp", "domain-specs");
            } catch (IOException e) {
                throw new ProvisioningException("Failed to create a tmp file", e);
            }
        }
        return domainSpecsFile;
    }

    protected FeatureSpec getInheritedSpec(String name) throws ProvisioningException {
        FeatureSpec spec = parsedInheritedSpecs.get(name);
        if (spec != null) {
            return spec;
        }
        final Path path = inheritedSpecs.get(name);
        if (path == null) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            spec = FeatureSpecXmlParser.getInstance().parse(reader);
        } catch (IOException | XMLStreamException e) {
            throw new ProvisioningException("Failed to parse " + name + " spec " + path, e);
        }
        parsedInheritedSpecs = CollectionUtils.put(parsedInheritedSpecs, name, spec);
        return spec;
    }

    private static EmbeddedManagedProcess createStandaloneServer(String jbossHome, String minimumStability, String config) {
        String[] cmdArgs = getCmdArgs(minimumStability, config);
        return EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, cmdArgs);
    }

    private static EmbeddedManagedProcess createEmbeddedHc(String jbossHome, String minimumStability) {
        String[] cmdArgs = getCmdArgs(minimumStability, null);
        return EmbeddedProcessFactory.createHostController(jbossHome, null, null, cmdArgs);
    }

    private static String[] getCmdArgs(String mimimumStability, String serverConfig) {
        List<String> args = new ArrayList<>();
        args.add("--admin-only");
        if (mimimumStability != null && !mimimumStability.isEmpty()) {
            args.add("--stability=" + mimimumStability);
        }
        if (serverConfig != null) {
            args.add("--server-config=" + serverConfig);
        }
        return args.toArray(String[]::new);
    }

    private static ModelNode generateModel(final EmbeddedManagedProcess server, Boolean all) throws ProvisioningException {
        try {
            server.start();
            if (!all) {
                return readModel(server);
            }
            return readAll(server);
        } catch (EmbeddedProcessStartException ex) {
            throw new ProvisioningException("Failed to read feature spec descriptions", ex);
        } finally {
            server.stop();
        }
    }

    private static ModelNode readModel(final EmbeddedManagedProcess server) throws ProvisioningException {
        List<String> subsystems = listSubsystems(server);
        ModelNode result = new ModelNode();
        ModelNode subsystemNodes = result.get("children").get(ClientConstants.SUBSYSTEM).get("model-description");
        for (String subsystem : subsystems) {
            ModelNode address = Operations.createAddress(ClientConstants.SUBSYSTEM, subsystem);
            ModelNode subsystemDescription = readResourceDescription(server, address);
            subsystemDescription.get(ClientConstants.ADDRESS).add(address);
            subsystemNodes.get(subsystem).set(subsystemDescription);
        }
        ModelNode address = Operations.createAddress(ClientConstants.DEPLOYMENT);
        result.get("children").get(ClientConstants.DEPLOYMENT).get("model-description").get("*").set(readResourceDescription(server, address));
        result.remove(ClientConstants.RESULT);
        return result;
    }

    private static ModelNode readAll(final EmbeddedManagedProcess server) throws ProvisioningException {
        ModelNode rootAddress = Operations.createAddress().setEmptyList();
        final ModelNode op = Operations.createOperation("read-resource-description", rootAddress);
        op.get(ClientConstants.RECURSIVE).set(true);
        final ModelNode result;
        try {
            result = server.getModelControllerClient().execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read feature descriptions", e);
        }
        if (!Operations.isSuccessfulOutcome(result)) {
            throw new ProvisioningException(Operations.getFailureDescription(result).asString());
        }
        return result.get(ClientConstants.RESULT);
    }

    private static List<String> listSubsystems(final EmbeddedManagedProcess server) throws ProvisioningException {
        ModelNode rootAddress = Operations.createAddress().setEmptyList();
        final ModelNode op = Operations.createOperation(ClientConstants.READ_CHILDREN_NAMES_OPERATION, rootAddress);
        op.get(ClientConstants.CHILD_TYPE).set(ClientConstants.SUBSYSTEM);
        op.get("include-singletons").set(true);
        final ModelNode result;
        try {
            result = server.getModelControllerClient().execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read feature descriptions", e);
        }
        if (!Operations.isSuccessfulOutcome(result)) {
            throw new ProvisioningException(Operations.getFailureDescription(result).asString());
        }
        List<String> names = new ArrayList<>();
        for (ModelNode name : result.get(ClientConstants.RESULT).asList()) {
            names.add(name.asString());
        }
        return names;
    }

    private static ModelNode readResourceDescription(final EmbeddedManagedProcess server, ModelNode address) throws ProvisioningException {
        final ModelNode op = Operations.createOperation("read-resource-description", address);
        op.get(ClientConstants.RECURSIVE).set(true);
        final ModelNode opResult;
        try {
            opResult = server.getModelControllerClient().execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read feature descriptions", e);
        }
        if (!Operations.isSuccessfulOutcome(opResult)) {
            throw new ProvisioningException(Operations.getFailureDescription(opResult).asString());
        }
        final ModelNode result = opResult.get(ClientConstants.RESULT);
        if (ModelType.LIST == result.getType()) {
            ModelNode finalResult = new ModelNode().setEmptyList();
            for (ModelNode node : result.asList()) {
                if (node.hasDefined(ClientConstants.RESULT)) {
                    finalResult.add(node.get(ClientConstants.RESULT));
                }
            }
            return finalResult;
        }
        return result;
    }

    private static ModelNode readFeatureSpecs(final EmbeddedManagedProcess server) throws ProvisioningException {
        try {
            server.start();
            final ModelNode op = Operations.createOperation("read-feature-description");
            op.get(ClientConstants.RECURSIVE).set(true);
            final ModelNode result;
            try {
                result = server.getModelControllerClient().execute(op);
            } catch (IOException e) {
                throw new ProvisioningException("Failed to read feature descriptions", e);
            }
            if (!Operations.isSuccessfulOutcome(result)) {
                throw new ProvisioningException(Operations.getFailureDescription(result).asString());
            }
            return result.require(ClientConstants.RESULT).require("feature");
        } catch (EmbeddedProcessStartException ex) {
            throw new ProvisioningException("Failed to read feature spec descriptions", ex);
        } finally {
            server.stop();
        }
    }

    private ModelNode readSpecsFile(Path specsFile) throws ProvisioningException {
        try (InputStream is = Files.newInputStream(specsFile)) {
            return ModelNode.fromStream(is);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(specsFile), e);
        }
    }

    private void writeSpecsFile(Path specsFile, ModelNode specs) throws ProvisioningException {
        try (BufferedWriter writer = Files.newBufferedWriter(specsFile)) {
            writer.write(specs.asString());
        } catch (IOException e) {
            throw new ProvisioningException(Errors.writeFile(specsFile), e);
        }
    }

    private void writeModelFile(Path specsFile, ModelNode specs) throws ProvisioningException {
        try (BufferedWriter writer = Files.newBufferedWriter(specsFile)) {
            writer.write(specs.toJSONString(false));
        } catch (IOException e) {
            throw new ProvisioningException(Errors.writeFile(specsFile), e);
        }
    }

    void warn(String str) {
        System.out.println("WARN: " + str);
    }

    void debug(String str, Object... args) {
        if (debug) {
            System.out.println(String.format(str, args));
        }
    }
}
