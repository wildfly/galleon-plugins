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

import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT;
import static org.jboss.as.controller.client.helpers.ClientConstants.SUBSYSTEM;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.controller.client.ModelControllerClient;
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

    private static final String CHILDREN = "children";
    private static final String DESCRIPTION = "description";
    private static final String MODEL_DESCRIPTION = "model-description";
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
    private String description;
    private boolean generateCompleteModel;

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
            String mimimumStability, String description, boolean generateCompleteModel, boolean fork, boolean debug) {
        this.installation = installation;
        this.outputDir = outputDir;
        this.fork = fork;
        this.debug = debug;
        this.inheritedSpecs = inheritedSpecs;
        this.mimimumStability = mimimumStability;
        this.description = description == null ? "" : description;
        this.generateCompleteModel = generateCompleteModel;
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
            ForkedEmbeddedUtil.fork(this, debug, getStoredSystemProps(), installation, getStandaloneSpecsFile().toString(), getDomainSpecsFile().toString(), description , generateCompleteModel ? "true" : "false", minStab);
            standaloneFeatures = readSpecsFile(getStandaloneSpecsFile());
            Path managementApiPath = getStandaloneSpecsFile().getParent().resolve("management-api.json");
            try {
                Files.createDirectories(outputDir);
                Files.copy(managementApiPath, outputDir.resolve("management-api.json"));
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
                ModelNode result = generateModel(createStandaloneServer(installation, mimimumStability, generateCompleteModel ? "standalone.xml" : "standalone-local.xml"), generateCompleteModel, description);
                try {
                    if (!Files.exists(outputDir)) {
                        Files.createDirectories(outputDir);
                    }
                    Files.write(outputDir.resolve("management-api.json"), result.toJSONString(false).getBytes());
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
        ModelNode features = new ModelNode();
        final FeatureSpecNode rootNode = new FeatureSpecNode(this, FeatureSpecNode.STANDALONE_MODEL, standaloneFeatures.require(ClientConstants.NAME).asString(), standaloneFeatures, features, generateCompleteModel);

        if (domainRoots != null) {
            rootNode.setDomainDescr(WfConstants.DOMAIN, new ModelNode());
            rootNode.generateDomain = false;
            for (Property child : domainRoots.get(CHILDREN).asPropertyList()) {
                final String specName = child.getName();
                if (specName.equals(WfConstants.HOST)) {
                    rootNode.setHostDescr(specName, child.getValue());
                } else if (specName.equals(WfConstants.PROFILE)) {
                    rootNode.setProfileDescr(specName, child.getValue());
                } else {
                    rootNode.domainDescr.get(CHILDREN).add(specName, child.getValue());
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
        try {
            // Sort features to produce a sorted features.json file
            Set<String> sortedKeys = features.keys().stream()
                    .sorted().collect(Collectors.toCollection(LinkedHashSet::new));
            ModelNode sortedFeatures = new ModelNode();
            for (String key : sortedKeys) {
                sortedFeatures.get(key).set(features.get(key));
            }
            Files.write(outputDir.resolve("features.json"), sortedFeatures.toJSONString(false).getBytes());
        } catch (IOException ex) {
            throw new ProvisioningException(ex);
        }
    }

    @Override
    public void forkedForEmbedded(String... args) throws ConfigGeneratorException {
        if(args.length != 5 && args.length != 6) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, Arrays.asList(args));
            throw new IllegalArgumentException("Expected 5-6 arguments but got " + Arrays.asList(args));
        }
        try {
            String description = args.length > 3 ? args[3] : null;
            Boolean generateCompleteModel = args.length > 4 ? Boolean.valueOf(args[4]) : Boolean.FALSE;
            String mimimumStability = args.length == 6 ? args[5] : null;
            ModelNode result = readFeatureSpecs(createStandaloneServer(args[0], mimimumStability, null));
            writeSpecsFile(Paths.get(args[1]), result);
            ModelNode resultModel = generateModel(createStandaloneServer(args[0], mimimumStability, generateCompleteModel ? "standalone.xml" : "standalone-local.xml"), generateCompleteModel, description);
            writeModelFile(Paths.get(args[1]).toAbsolutePath().getParent().resolve("management-api.json"), resultModel);
            System.out.println("FORKED TO " + Paths.get(args[1]).toAbsolutePath().getParent().resolve("management-api.json"));
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

    private static ModelNode generateModel(final EmbeddedManagedProcess server, Boolean all, String description) throws ProvisioningException {
        try {
            server.start();
            final ModelNode model;
            if (!all) {
                model = readModel(server, description);
            } else {
                model = readAll(server, description);
            }
            model.get("possible-capabilities").set(getPossibleCapabilities(server.getModelControllerClient()));
            return model;
        } catch (EmbeddedProcessStartException ex) {
            throw new ProvisioningException("Failed to read feature spec descriptions", ex);
        } finally {
            server.stop();
        }
    }

    private static ModelNode getPossibleCapabilities(ModelControllerClient client) throws ProvisioningException {
        final ModelNode address = Operations.createAddress("core-service", "capability-registry");
        ModelNode result;
        try {
            ModelNode op = Operations.createReadAttributeOperation(address, "possible-capabilities");
            result = client.execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read capabilities", e);
        }
        if (!Operations.isSuccessfulOutcome(result)) {
            throw new ProvisioningException(Operations.getFailureDescription(result).asString());
        }
        return Operations.readResult(result);
    }

    private static ModelNode readModel(final EmbeddedManagedProcess server, String description) throws ProvisioningException {
        List<String> subsystems = listSubsystems(server);
        ModelNode result = new ModelNode().setEmptyObject();
        ModelNode subsystemNodes = result.get(CHILDREN).get(SUBSYSTEM).get(MODEL_DESCRIPTION);
        for (String subsystem : subsystems) {
            ModelNode address = Operations.createAddress(SUBSYSTEM, subsystem);
            ModelNode subsystemDescription = readResourceDescription(server, address, true);
            subsystemDescription.get(ClientConstants.ADDRESS).add(address);
            subsystemNodes.get(subsystem).set(subsystemDescription);
        }
        ModelNode subsystemsDescription = readResourceDescription(server, Operations.createAddress().setEmptyList(), false);
        ModelNode deploymentDescription = readResourceDescription(server, Operations.createAddress(DEPLOYMENT), false);
        if (deploymentDescription.hasDefined(DESCRIPTION)) {
            deploymentDescription = deploymentDescription.get(DESCRIPTION);
        }
        ModelNode deploymentSubsystems = listDeploymentSubsystems(server, subsystems);
        ModelNode subsystemDescription = subsystemsDescription.get(CHILDREN).get(SUBSYSTEM).get(DESCRIPTION);
        if (deploymentSubsystems.isDefined() && !deploymentSubsystems.asPropertyList().isEmpty()) {
            result.get(CHILDREN).get(DEPLOYMENT).get(MODEL_DESCRIPTION).get("*").get(CHILDREN).get(SUBSYSTEM).get(DESCRIPTION).set(subsystemDescription);
            result.get(CHILDREN).get(DEPLOYMENT).get(MODEL_DESCRIPTION).get("*").get(CHILDREN).get(SUBSYSTEM).get(MODEL_DESCRIPTION).set(deploymentSubsystems);
            result.get(CHILDREN).get(DEPLOYMENT).get(MODEL_DESCRIPTION).get("*").get(DESCRIPTION).set(deploymentDescription);
            result.get(CHILDREN).get(DEPLOYMENT).get(DESCRIPTION).set(subsystemsDescription.get(CHILDREN).get(DEPLOYMENT).get(DESCRIPTION));
        }
        result.remove(ClientConstants.RESULT);
        result.get(CHILDREN).get(SUBSYSTEM).get(DESCRIPTION).set(subsystemDescription);
        result.get(DESCRIPTION).set(description);
        return result;
    }

    private static ModelNode readAll(final EmbeddedManagedProcess server, String description) throws ProvisioningException {
        ModelNode rootAddress = Operations.createAddress().setEmptyList();
        final ModelNode op = Operations.createOperation("read-resource-description", rootAddress);
        op.get(ClientConstants.RECURSIVE).set(true);
        op.get("operations").set(true);
        op.get("inherited").set(false);
        final ModelNode result;
        try {
            result = server.getModelControllerClient().execute(op);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read feature descriptions", e);
        }
        if (!Operations.isSuccessfulOutcome(result)) {
            throw new ProvisioningException(Operations.getFailureDescription(result).asString());
        }
        final ModelNode  effectiveResult = result.get(ClientConstants.RESULT);
        effectiveResult.get(DESCRIPTION).set(description);
        return effectiveResult;
    }

    private static List<String> listSubsystems(final EmbeddedManagedProcess server) throws ProvisioningException {
        ModelNode rootAddress = Operations.createAddress().setEmptyList();
        final ModelNode op = Operations.createOperation(ClientConstants.READ_CHILDREN_NAMES_OPERATION, rootAddress);
        op.get(ClientConstants.CHILD_TYPE).set(SUBSYSTEM);
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

    private static ModelNode listDeploymentSubsystems(final EmbeddedManagedProcess server, List<String> subsystems) throws ProvisioningException {
        ModelNode details = new ModelNode().addEmptyObject();
        for (String subsystem : subsystems) {
            ModelNode address = Operations.createAddress(ClientConstants.DEPLOYMENT, "*", SUBSYSTEM, subsystem);
            try {
                final ModelNode opResult = readResourceDescription(server, address, true);
                details.get(subsystem).set(opResult);
            } catch (ProvisioningException ex) {
                System.out.println("Couldn't get deployment details for the subsystem " + subsystem);
            }
        }
        return details;
    }

    private static ModelNode readResourceDescription(final EmbeddedManagedProcess server, ModelNode address, boolean recursive) throws ProvisioningException {
        final ModelNode op = Operations.createOperation("read-resource-description", address);
        final boolean multi = isStarAddress(address);
        op.get(ClientConstants.RECURSIVE).set(recursive);
        op.get("operations").set(true);
        op.get("inherited").set(false);
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
            if (multi) {
                return result.get(0).get(ClientConstants.RESULT);
            }
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

    private static boolean isStarAddress(ModelNode address) {
        for (Property elt : address.asPropertyList()) {
            if ("*".equals(elt.getValue().asString())) {
                return true;
            }
        }
        return false;
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
