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
import java.util.Set;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.StringUtils;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ForkedEmbeddedUtil;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureSpecGenerator implements ForkedEmbeddedUtil.ForkCallback {

    private Map<String, FeatureSpecNode> nodesBySpecName = new HashMap<>();
    private Map<String, Map<String, FeatureSpecNode>> referencedSpecs = new HashMap<>();
    private Map<String, FeatureSpecNode> capProviders = new HashMap<>();
    private int specsGenerated;

    private String installation;
    Path outputDir;
    private boolean fork;
    private boolean debug;
    private Set<String> inheritedSpecs;

    private Path systemProps;
    private Path standaloneSpecsFile;
    private Path domainSpecsFile;

    String getBranchId(String spec, int dots) {
        int i = 0;
        int index = 0;
        while(i <= dots) {
            index = spec.indexOf('.', index + 1);
            if(index < 0) {
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
        if(specs != null) {
            if(specs.size() == 1) {
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
        return inheritedSpecs.contains(name);
    }

    void increaseSpecCount() {
        ++specsGenerated;
    }

    /**
     * This ctor has to be called only by reflection
     */
    public FeatureSpecGenerator() {
    }

    public FeatureSpecGenerator(String installation, Path outputDir, Set<String> inheritedSpecs, boolean fork, boolean debug) {
        this.installation = installation;
        this.outputDir = outputDir;
        this.fork = fork;
        this.debug = debug;
        this.inheritedSpecs = inheritedSpecs;
    }

    public int generateSpecs() throws ProvisioningException {
        final Map<Object, Object> originalProps = new HashMap<>(System.getProperties());
        try {
            doGenerate(installation.toString());
        } finally {
            final List<String> toClear = new ArrayList<>();
            for(Map.Entry<Object, Object> prop : System.getProperties().entrySet()) {
                final Object value = originalProps.get(prop.getKey());
                if (value != null) {
                    System.setProperty(prop.getKey().toString(), value.toString());
                } else {
                    toClear.add(prop.getKey().toString());
                }
            }
            for(String prop : toClear) {
                System.clearProperty(prop);
            }
            if(systemProps != null) {
                IoUtils.recursiveDelete(systemProps);
            }
            if(standaloneSpecsFile != null) {
                IoUtils.recursiveDelete(standaloneSpecsFile);
            }
            if(domainSpecsFile != null) {
                IoUtils.recursiveDelete(domainSpecsFile);
            }
        }
        return specsGenerated;
    }

    private void doGenerate(String installationHome) throws ProvisioningException {

        final ModelNode standaloneFeatures;
        final ModelNode domainRoots;
        if(fork) {
            ForkedEmbeddedUtil.fork(this, getStoredSystemProps(), installation, getStandaloneSpecsFile().toString(), getDomainSpecsFile().toString());
            standaloneFeatures = readSpecsFile(getStandaloneSpecsFile());
            domainRoots = readSpecsFile(getDomainSpecsFile());
        } else {
            standaloneFeatures = readFeatureSpecs(createStandaloneServer(installation));
            domainRoots = readFeatureSpecs(createEmbeddedHc(installation));
        }

        final FeatureSpecNode rootNode = new FeatureSpecNode(this, FeatureSpecNode.STANDALONE_MODEL, standaloneFeatures.require(ClientConstants.NAME).asString(), standaloneFeatures);

        rootNode.setDomainDescr(WfConstants.DOMAIN, new ModelNode());
        rootNode.generateDomain = false;
        for(Property child : domainRoots.get("children").asPropertyList()) {
            final String specName = child.getName();
            if(specName.equals(WfConstants.HOST)) {
                rootNode.setHostDescr(specName, child.getValue());
            } else if(specName.equals(WfConstants.PROFILE)) {
                rootNode.setProfileDescr(specName, child.getValue());
            } else {
                rootNode.domainDescr.get("children").add(specName, child.getValue());
            }
        }

        rootNode.processChildren(FeatureSpecNode.STANDALONE_MODEL);
        rootNode.processChildren(FeatureSpecNode.PROFILE_MODEL);
        rootNode.processChildren(FeatureSpecNode.DOMAIN_MODEL);
        rootNode.processChildren(FeatureSpecNode.HOST_MODEL);

        rootNode.buildSpecs();
    }

    @Override
    public void forkedForEmbedded(String... args) throws ProvisioningException {
        if(args.length != 3) {
            final StringBuilder buf = new StringBuilder();
            StringUtils.append(buf, Arrays.asList(args));
            throw new IllegalArgumentException("Expected 3 arguments but got " + Arrays.asList(args));
        }
        ModelNode result = readFeatureSpecs(createStandaloneServer(args[0]));
        writeSpecsFile(Paths.get(args[1]), result);
        result = readFeatureSpecs(createEmbeddedHc(args[0]));
        writeSpecsFile(Paths.get(args[2]), result);
    }

    @Override
    public void forkedEmbeddedMessage(String msg) {
        if(debug) {
            System.out.println(msg);
        }
    }

    protected Path getStoredSystemProps() throws ProvisioningException {
        if(systemProps == null) {
            systemProps = ForkedEmbeddedUtil.storeSystemProps();
        }
        return systemProps;
    }

    protected Path getStandaloneSpecsFile() throws ProvisioningException {
        if(standaloneSpecsFile == null) {
            try {
                standaloneSpecsFile = Files.createTempFile("wfgp", "standalone-specs");
            } catch (IOException e) {
                throw new ProvisioningException("Failed to create a tmp file", e);
            }
        }
        return standaloneSpecsFile;
    }

    protected Path getDomainSpecsFile() throws ProvisioningException {
        if(domainSpecsFile == null) {
            try {
                domainSpecsFile = Files.createTempFile("wfgp", "domain-specs");
            } catch (IOException e) {
                throw new ProvisioningException("Failed to create a tmp file", e);
            }
        }
        return domainSpecsFile;
    }

    private static EmbeddedManagedProcess createStandaloneServer(String jbossHome) {
        return EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, new String[] {"--admin-only"});
    }

    private static EmbeddedManagedProcess createEmbeddedHc(String jbossHome) {
        return EmbeddedProcessFactory.createHostController(jbossHome, null, null, new String[] {"--admin-only"});
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
            if(!Operations.isSuccessfulOutcome(result)) {
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
        try(BufferedWriter writer = Files.newBufferedWriter(specsFile)) {
            writer.write(specs.asString());
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
