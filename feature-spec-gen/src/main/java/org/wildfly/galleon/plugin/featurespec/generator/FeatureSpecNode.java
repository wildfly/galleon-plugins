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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;

import javax.xml.stream.XMLStreamException;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.Constants;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureReferenceSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.xml.FeatureSpecXmlWriter;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
class FeatureSpecNode {

    static final int STANDALONE_MODEL = 1;
    static final int PROFILE_MODEL = 2;
    static final int DOMAIN_MODEL = 4;
    static final int HOST_MODEL = 8;

    private static final String DOMAIN_PREFIX = "domain.";
    private static final String HOST_PREFIX = "host.";
    private static final String PROFILE_PREFIX = "profile.";

    private static final String CORE_SERVICE_MANAGEMENT = "core-service.management";
    private static final String EXTENSION = "extension";
    private static final String SUBSYSTEM_PREFIX = "subsystem.";

    private static boolean identicalInAllModels(String specName) {
        return specName.startsWith(SUBSYSTEM_PREFIX) ||

                specName.startsWith(CORE_SERVICE_MANAGEMENT) && (
                        specName.length() == CORE_SERVICE_MANAGEMENT.length() ||
                                specName.regionMatches(CORE_SERVICE_MANAGEMENT.length(), ".access", 0, ".access".length()) ||
                                specName.regionMatches(CORE_SERVICE_MANAGEMENT.length(), ".security-realm", 0, ".security-realm".length()) ||
                                specName.regionMatches(CORE_SERVICE_MANAGEMENT.length(), ".ldap-connection", 0, ".ldap-connection".length())) ||

                specName.equals(EXTENSION);
    }

    private static boolean sameRequiredCapabilities(ModelNode standaloneDescr, ModelNode domainDescr, String optionalPrefix, boolean failIfDifferent) throws ProvisioningException {
        Map<String, Boolean> standaloneCaps = Collections.emptyMap();
        if (standaloneDescr.hasDefined("requires")) {
            final List<ModelNode> capsDescr = standaloneDescr.get("requires").asList();
            standaloneCaps = new HashMap<>(capsDescr.size());
            for (ModelNode capability : capsDescr) {
                standaloneCaps.put(capability.get("name").asString(), capability.hasDefined("optional") && capability.get("optional").asBoolean());
            }
        }
        final List<ModelNode> capsDescr = domainDescr.hasDefined("requires") ? domainDescr.get("requires").asList() : Collections.emptyList();
        if (capsDescr.size() != standaloneCaps.size()) {
            if (failIfDifferent) {
                final List<String> domainCaps = new ArrayList<>(capsDescr.size());
                for (ModelNode capability : capsDescr) {
                    domainCaps.add(capability.get("name").asString());
                }
                throw new ProvisioningException("Required capabilities in standalone model " + standaloneCaps.keySet() + " do not match required capabilities in the domain model " + domainCaps);
            }
            return false;
        }
        for (ModelNode capability : capsDescr) {
            String domainName = capability.get("name").asString();
            if(optionalPrefix != null && domainName.charAt(0) == '$' && domainName.startsWith(optionalPrefix, 1)) {
                domainName = domainName.substring(optionalPrefix.length() + 1);
            }
            final Boolean standaloneOptional = standaloneCaps.get(domainName);
            final boolean domainOptional = capability.hasDefined("optional") && capability.get("optional").asBoolean();
            if (domainOptional) {
                if (standaloneOptional == null || !standaloneOptional) {
                    if (failIfDifferent) {
                        throw new ProvisioningException("Required capability "
                                + capability.get("name").asString() + " is optional in the domain model spec unlike " + domainName + " in the standalone one");
                    }
                    return false;
                }
            } else if (standaloneOptional != null && standaloneOptional) {
                throw new ProvisioningException("Required capability " + domainName
                        + " is optional in the standalone model spec unlike " + capability.get("name").asString() + " in the domain one");
            }
        }
        return true;
    }

    private static boolean sameProvidedCapabilities(ModelNode standaloneDescr, ModelNode domainDescr, String optionalPrefix, boolean failIfDifferent) throws ProvisioningException {
        Set<String> standaloneCaps = Collections.emptySet();
        if (standaloneDescr.hasDefined("provides")) {
            final List<ModelNode> capsDescr = standaloneDescr.get("provides").asList();
            standaloneCaps = new HashSet<>(capsDescr.size());
            for (ModelNode capability : capsDescr) {
                standaloneCaps.add(capability.asString());
            }
        }
        final List<ModelNode> capsDescr = domainDescr.hasDefined("provides") ? domainDescr.get("provides").asList() : Collections.emptyList();
        if (capsDescr.size() != standaloneCaps.size()) {
            if (failIfDifferent) {
                throw new ProvisioningException("Provided capabilities don't match");
            }
            return false;
        }
        for (ModelNode capability : capsDescr) {
            String domainName = capability.asString();
            if(optionalPrefix != null && domainName.charAt(0) == '$' && domainName.startsWith(optionalPrefix, 1)) {
                domainName = domainName.substring(optionalPrefix.length() + 1);
            }
            if(!standaloneCaps.contains(domainName)) {
                if (failIfDifferent) {
                    throw new ProvisioningException("Domain model spec provides capability " + capability.asString() + " with no equivalent in the standalone one");
                }
                return false;
            }
        }
        return true;
    }

    private static boolean samePackages(ModelNode standaloneDescr, ModelNode domainDescr, boolean failIfDifferent) throws ProvisioningException {
        Set<String> standalonePackages = Collections.emptySet();
        if (standaloneDescr.hasDefined("packages")) {
            final List<ModelNode> packagesDescr = standaloneDescr.get("packages").asList();
            standalonePackages = new HashSet<>(packagesDescr.size());
            for (ModelNode packageDep : packagesDescr) {
                standalonePackages.add(packageDep.require("package").asString());
            }
        }
        final List<ModelNode> packagesDescr = domainDescr.hasDefined("packages") ? domainDescr.get("packages").asList() : Collections.emptyList();
        if(packagesDescr.size() != standalonePackages.size()) {
            if(failIfDifferent) {
                throw new ProvisioningException("Packages dependencies don't match");
            }
            return false;
        }
        for (ModelNode packageDep : packagesDescr) {
            if(!standalonePackages.contains(packageDep.require("package").asString())) {
                if(failIfDifferent) {
                    throw new ProvisioningException("Domain model feature requires package " + packageDep.require("package").asString() + " unlike the standalone one");
                }
                return false;
            }
        }
        return true;
    }

    private static boolean sameAnnotations(ModelNode standaloneDescr, ModelNode domainDescr, String domainNode, boolean failIfDifferent) throws ProvisioningException {
        final ModelNode standaloneAnnot = standaloneDescr.hasDefined("annotation") ? standaloneDescr.require("annotation") : null;
        final ModelNode domainAnnot = domainDescr.hasDefined("annotation") ? domainDescr.require("annotation") : null;

        if(standaloneAnnot == null) {
            if(domainAnnot == null) {
                return true;
            }
            if(failIfDifferent) {
                throw new ProvisioningException("Standalone model feature does not include annotation unlike the corresponding domain model one");
            }
            return false;
        }
        if(domainAnnot == null) {
            if(failIfDifferent) {
                throw new ProvisioningException("Domain model feature does not include annotation unlike the corresponding standalone model one");
            }
            return false;
        }
        if(!standaloneAnnot.require("name").asString().equals(domainAnnot.require("name").asString())) {
            if(failIfDifferent) {
                throw new ProvisioningException("Standalone model annotation is " + standaloneAnnot.get("name").asString() + " while the domain model one is " + domainAnnot.get("name").asString());
            }
            return false;
        }
        final Set<String> standaloneElems = standaloneAnnot.keys();
        final Set<String> domainElems = domainAnnot.keys();
        String domainOpParamsMapping = null;
        switch(domainElems.size() - standaloneElems.size()) {
            case 0:
                break;
            case 1:
                if(domainAnnot.has("op-params-mapping") && !standaloneAnnot.has("op-params-mapping")) {
                    domainOpParamsMapping = domainAnnot.get("op-params-mapping").asString();
                    break;
                }
            default:
                if(failIfDifferent) {
                    throw new ProvisioningException("Annotation elements in the standalone model spec " + standaloneAnnot.keys()
                            + " don't match the corresponding annotation elements in the domain spec " + domainAnnot.keys());
                }
        }

        if(!domainAnnot.get("name").asString().equals(standaloneAnnot.get("name").asString())) {
            throw new ProvisioningException("Annotation element 'name' in set to " + domainAnnot.get("name").asString() + " in the domain model and to " + standaloneAnnot.get("name").asString() + " in standalone");
        }

        String domainAddrParams = domainAnnot.get("addr-params").asString();
        if(domainAddrParams.startsWith(domainNode)) {
            domainAddrParams = domainAddrParams.substring(domainNode.length() + 1);
        }
        if(!domainAddrParams.equals(standaloneAnnot.get("addr-params").asString())) {
            throw new ProvisioningException("Annotation element 'addr-params' in set to " + domainAddrParams + " in the domain model and to " + standaloneAnnot.get("addr-params").asString() + " in standalone");
        }

        String domainOpParams = domainAnnot.get("op-params").asString();
        if(domainOpParams.startsWith(domainNode)) {
            domainOpParams = domainOpParams.substring(domainNode.length() + 1);
        }
        final String standaloneOpParams = standaloneAnnot.get("op-params").asString();
        if(!domainOpParams.equals(standaloneOpParams)) {
            if(domainOpParamsMapping != null) {
                if(!domainOpParamsMapping.equals(standaloneOpParams)) {
                    throw new ProvisioningException("Annotation element 'op-params' is set to " + domainOpParamsMapping
                            + " in the domain model and to " + standaloneOpParams + " in standalone");
                }
            } else {
                throw new ProvisioningException("Annotation element 'name' is set to " + domainOpParams
                        + " in the domain model and to " + standaloneOpParams + " in standalone");
            }
        }

        int checkedStandaloneElems = 3;
        if(standaloneAnnot.has("op-params-mapping") && domainOpParamsMapping == null) {
            ++checkedStandaloneElems;
            String standaloneOpParamsMapping = standaloneAnnot.get("op-params-mapping").asString();
            domainOpParamsMapping = domainAnnot.get("op-params-mapping").asString();
            if(!domainOpParamsMapping.equals(standaloneOpParamsMapping)) {
                throw new ProvisioningException("Annotation element 'op-params-mapping' in set to " + domainOpParamsMapping
                        + " in the domain model and to " + standaloneOpParamsMapping + " in standalone");
            }
        }

        if(standaloneAnnot.has("complex-attribute")) {
            if(!domainAnnot.has("complex-attribute")) {
                if(failIfDifferent) {
                    throw new ProvisioningException("Domain annotation is missing complex-attribute element");
                }
                return false;
            }
            if(!domainAnnot.get("complex-attribute").equals(standaloneAnnot.get("complex-attribute"))) {
                if(failIfDifferent) {
                    throw new ProvisioningException("Annotation element 'complex-attribute' is set to " + standaloneAnnot.get("complex-attribute").asString() +
                            " in the standalone feature spec and to " + domainAnnot.get("complex-attribute").asString() + " in the domain one");
                }
                return false;
            }
            ++checkedStandaloneElems;
        }

        if(standaloneElems.size() > checkedStandaloneElems) {
            throw new ProvisioningException("Expected " + checkedStandaloneElems + " annotation elements in the standalone model but got " + standaloneElems);
        }
        return true;
    }

    private static boolean sameFeatureRefs(ModelNode standaloneDescr, ModelNode domainDescr, String optionalPrefix, boolean failIfDifferent) throws ProvisioningException {
        final List<ModelNode> standaloneRefs = standaloneDescr.hasDefined("refs") ? standaloneDescr.get("refs").asList() : Collections.emptyList();
        final List<ModelNode> domainRefs = domainDescr.hasDefined("refs") ? domainDescr.get("refs").asList() : Collections.emptyList();
        final Set<String> standaloneRefNames = new HashSet<>(standaloneRefs.size());
        for(ModelNode ref : standaloneRefs) {
            standaloneRefNames.add(ref.get("feature").asString());
        }
        int skippedDomainRoot = 0;
        for(ModelNode ref : domainRefs) {
            String domainRefName = ref.get("feature").asString();
            if(optionalPrefix != null) {
                if(domainRefName.regionMatches(0, optionalPrefix, 0, optionalPrefix.length() - 1)) {
                    if (domainRefName.startsWith(optionalPrefix)) {
                        domainRefName = domainRefName.substring(optionalPrefix.length());
                    } else {
                        skippedDomainRoot = 1;
                        continue;
                    }
                } else if(optionalPrefix.equals(PROFILE_PREFIX) && domainRefName.startsWith(DOMAIN_PREFIX)) {
                    domainRefName = domainRefName.substring(DOMAIN_PREFIX.length());
                }
            }
            if(!standaloneRefNames.contains(domainRefName)) {
                if(failIfDifferent) {
                    throw new ProvisioningException("Domain model spec includes reference " + ref.get("feature").asString() + " while the standalone one does not");
                }
                return false;
            }
        }

        if(standaloneRefs.size() != domainRefs.size() - skippedDomainRoot) {
            if(failIfDifferent) {
                throw new ProvisioningException("The number of references is different in standalone and domain specs");
            }
            return false;
        }

        return true;
    }

    private static boolean areIdentical(ModelNode standaloneDescr, ModelNode domainDescr, String optionalPrefix, boolean failIfDifferent) throws ProvisioningException {
        return sameRequiredCapabilities(standaloneDescr, domainDescr, optionalPrefix, failIfDifferent)
                && sameProvidedCapabilities(standaloneDescr, domainDescr, optionalPrefix, failIfDifferent)
                && samePackages(standaloneDescr, domainDescr, failIfDifferent)
                && sameAnnotations(standaloneDescr, domainDescr, optionalPrefix == null ? null : optionalPrefix.substring(0, optionalPrefix.length() - 1), failIfDifferent)
                && sameFeatureRefs(standaloneDescr, domainDescr, optionalPrefix, failIfDifferent);
    }

    private static void assertIdenticalSpecs(String spec1, ModelNode descr1, String spec2, ModelNode descr2, String optionalPrefix) throws ProvisioningException {
        try {
            areIdentical(descr1, descr2, optionalPrefix, true);
        } catch (ProvisioningException e) {
            throw new ProvisioningException("Feature spec " + spec1 + " does not match the corresponding feature spec " + spec2, e);
        }
    }

    private static FeatureAnnotation getAnnotation(ModelNode descr) {
        if (!descr.hasDefined("annotation")) {
            return null;
        }
        final ModelNode annotationNode = descr.require("annotation");
        final FeatureAnnotation annotation = new FeatureAnnotation(WfConstants.JBOSS_OP);
        for (Property property : annotationNode.asPropertyList()) {
            annotation.setElement(property.getName(), property.getValue().asString());
        }
        return annotation;
    }

    private static String convertToCli(String value) {
        if (value != null
                && !value.isEmpty()
                && value.indexOf(',') >= 0
                && !(value.startsWith("\"") && value.endsWith("\""))
                && !(value.startsWith("[") && value.endsWith("]"))) {
            return '\"' + value + '\"';
        }
        return value;
    }

    private void persistSpec(String name, ModelNode descr, int model) throws ProvisioningException {
        final FeatureSpec.Builder builder = FeatureSpec.builder(name);
        final FeatureAnnotation annotation = getAnnotation(descr);
        if(annotation != null) {
            builder.addAnnotation(annotation);
        }
        final String branchId = gen.getBranchId(name, 1);
        if(branchId != null) {
            builder.addAnnotation(FeatureAnnotation.featureBranch(branchId));
        }

        if (descr.hasDefined("requires")) {
            for (ModelNode capability : descr.require("requires").asList()) {
                builder.requiresCapability(capability.get("name").asString(), capability.hasDefined("optional") && capability.get("optional").asBoolean());
            }
        }
        if (descr.hasDefined("provides")) {
            for (ModelNode capability : descr.require("provides").asList()) {
                builder.providesCapability(capability.asString());
            }
        }
        final List<ModelNode> paramsDescr = descr.require("params").asList();
        if (descr.hasDefined("refs")) {
            for (ModelNode ref : descr.get("refs").asList()) {
                final boolean isInclude = ref.hasDefined("include") && ref.get("include").asBoolean();
                final String featureRefName = ref.get("feature").asString();
                final FeatureReferenceSpec.Builder refBuilder = FeatureReferenceSpec.builder(featureRefName).setInclude(isInclude);
                final boolean paramMapping = ref.hasDefined("mappings");

                final FeatureSpecNode targetSpec = gen.getSpec(featureRefName);
                final FeatureSpec inheritedSpec = targetSpec.isGenerate(model) ? null : gen.getInheritedSpec(featureRefName);
                if(inheritedSpec != null) {
                    for(FeatureParameterSpec refParam : inheritedSpec.getIdParams()) {
                        boolean present = false;
                        for (ModelNode param : paramsDescr) {
                            if(param.get("name").asString().equals(refParam.getName())) {
                                present = true;
                                break;
                            }
                        }
                        if(!present) {
                            gen.debug("Adding ID parameter %s to %s to satisfy reference %s parameter mapping ", refParam.getName(), name, featureRefName);
                            if(refParam.hasDefaultValue()) {
                                builder.addParam(refParam);
                            } else {
                                builder.addParam(FeatureParameterSpec.create(refParam.getName(), true, false, Constants.GLN_UNDEFINED));
                            }
                            if(paramMapping) {
                                refBuilder.mapParam(refParam.getName(), refParam.getName());
                            }
                        }
                    }
                }

                if (paramMapping) {
                    for (Property mapping : ref.require("mappings").asPropertyList()) {
                        refBuilder.mapParam(mapping.getName(), mapping.getValue().asString());
                    }
                }
                if (ref.hasDefined("nillable") && ref.get("nillable").asBoolean()) {
                    refBuilder.setNillable(true);
                }
                builder.addFeatureRef(refBuilder.build());
            }
        }
        if (descr.hasDefined("params")) {
            for (ModelNode param : paramsDescr) {
                final FeatureParameterSpec.Builder featureParamSpecBuilder = FeatureParameterSpec.builder(param.get("name").asString());
                if (param.hasDefined("feature-id") && param.get("feature-id").asBoolean()) {
                    featureParamSpecBuilder.setFeatureId();
                }
                if (param.hasDefined("nillable") && param.get("nillable").asBoolean()) {
                    featureParamSpecBuilder.setNillable();
                }
                featureParamSpecBuilder.setDefaultValue(param.hasDefined("default") ? convertToCli(param.get("default").asString()) : null);
                if (param.hasDefined("type")) {
                    featureParamSpecBuilder.setType(param.get("type").asString());
                }
                builder.addParam(featureParamSpecBuilder.build());
            }
        }
        if (descr.hasDefined("packages")) {
            for (ModelNode packageDep : descr.get("packages").asList()) {
                if (packageDep.hasDefined("package")) {
                    builder.addPackageDep(packageDep.require("package").asString());
                }
            }
        }
        final FeatureSpec spec = builder.build();
        final Path specDir = gen.outputDir.resolve(spec.getName());
        if (Files.notExists(specDir)) {
            try {
                Files.createDirectories(specDir);
            } catch (IOException e) {
                throw new ProvisioningException(Errors.mkdirs(specDir));
            }
        }
        try {
            FeatureSpecXmlWriter.getInstance().write(spec, specDir.resolve("spec.xml"));
        } catch (XMLStreamException | IOException e) {
            throw new ProvisioningException(Errors.writeFile(specDir.resolve("spec.xml")), e);
        }
        gen.increaseSpecCount();
    }

    private void ensureIdParams(String specName, ModelNode descr, Map<String, ModelNode> descrParams, Set<String> extendedIdParams, boolean addAsIds) throws ProvisioningException {
        final ModelNode params = descr.get("params");
        ModelNode annotation = null;
        ModelNode addrParams = null;
        if(descr.hasDefined("annotation")) {
            annotation = descr.get("annotation");
            addrParams = annotation.hasDefined("addr-params") ? annotation.get("addr-params") : null;
        }
        String newAddrParams = null;
        for(String param : extendedIdParams) {
            ModelNode paramDescr = descrParams.get(param);
            if(paramDescr != null) {
                if (paramDescr.hasDefined("feature-id") && paramDescr.get("feature-id").asBoolean()) {
                    continue;
                }
                paramDescr.get("name").set(param + "-feature");
                gen.debug("re-named param %s to %s-feature in spec %s", param, param, specName);

                if(addrParams == null) {
                    throw new ProvisioningException("The annotation has not been generated for " + descr);
                }
                final ModelNode opParams = annotation.hasDefined("op-params") ? annotation.get("op-params") : null;
                if(opParams != null) {
                    StringBuilder newOpParams = new StringBuilder();
                    final StringTokenizer opParamsTokens = new StringTokenizer(opParams.asString(), ",");
                    String mappedParam = opParamsTokens.nextToken();
                    newOpParams.append(mappedParam);
                    if(mappedParam.equals(param)) {
                        newOpParams.append("-feature");
                    }
                    while(opParamsTokens.hasMoreTokens()) {
                        mappedParam = opParamsTokens.nextToken();
                        newOpParams.append(',');
                        newOpParams.append(mappedParam);
                        if(mappedParam.equals(param)) {
                            newOpParams.append("-feature");
                        }
                    }
                    opParams.set(newOpParams.toString());
                }
            }

            final ModelNode idParam = new ModelNode();
            idParam.get("name").set(param);
            if(addAsIds) {
                idParam.get("feature-id").set(true);
            }
            idParam.get("default").set(Constants.GLN_UNDEFINED);
            params.add(idParam);

            if (addrParams == null) {
                continue;
            }
            if(newAddrParams == null) {
                newAddrParams = param + ',' + addrParams.asString();
            } else {
                newAddrParams = param + ',' + newAddrParams;
            }
        }
        if(newAddrParams != null) {
            addrParams.set(newAddrParams);
        }
    }

    private void ensureCapPrefix(ModelNode descr, String prefix, int mergeCode) throws ProvisioningException {
        if (descr.hasDefined("requires")) {
            for (ModelNode capability : descr.require("requires").asList()) {
                final String name = capability.get("name").asString();
                final FeatureSpecNode capProvider = gen.getCapProvider(getCapId(name));
                if(capProvider == null) {
                    gen.warn("NO PROVIDER found for capability " + name);
                } else if((capProvider.mergeCode & mergeCode) != mergeCode) {
                    continue;
                }
                if(name.startsWith(prefix)) {
                    continue;
                }
                capability.get("name").set(prefix + name);
            }
        }
        if (descr.hasDefined("provides")) {
            List<ModelNode> caps = descr.require("provides").asList();
            for(int i = 0; i < caps.size(); ++i) {
                final ModelNode capNode = caps.get(i);
                if(capNode.asString().startsWith(prefix)) {
                    continue;
                }
                capNode.set(prefix + capNode.asString());
            }
        }
    }

    private static String getCapId(String capName) {
        int i = capName.indexOf('$');
        if (i >= 0) {
            StringBuilder buf = new StringBuilder();
            int prevI = 0;
            while (i >= 0) {
                if (i >= prevI) {
                    buf.append(capName.substring(prevI, i + 1));
                    prevI = -1;
                }
                i = capName.indexOf('.', i + 1);
                if (i > 0) {
                    prevI = i;
                    i = capName.indexOf('$', prevI + 1);
                }
            }
            if (prevI > 0 && prevI <= capName.length()) {
                buf.append(capName.substring(prevI));
            }
            capName = buf.toString();
        }
        return capName;
    }

    private void updateReferencingSpecs(String fromReferencedSpec, String toReferencedSpec) throws ProvisioningException {
        final Map<String, FeatureSpecNode> referencingSpecs = gen.getReferencingSpecs(fromReferencedSpec);
        if(referencingSpecs.isEmpty()) {
            return;
        }
        gen.clearReferencingSpecs(fromReferencedSpec);

        final Iterator<Map.Entry<String, FeatureSpecNode>> i = referencingSpecs.entrySet().iterator();
        while(i.hasNext()) {
            final Entry<String, FeatureSpecNode> next = i.next();
            final String referencingSpecName = next.getKey();
            final FeatureSpecNode referencingSpec = next.getValue();

            Set<String> extendedIdParams;
            final ModelNode referencingDescr;

            final int referencingModel = referencingSpec.getModel(referencingSpecName);
            if((referencingSpec.mergeCode & referencingModel) > 0) {
                // the referencing spec is going to be merged
                referencingDescr = referencingSpec.mergedDescr;
                if(gen.isInherited(referencingSpec.mergedName)) {
                    continue;
                }

                extendedIdParams = Collections.emptySet();
                for(String candidateParam : this.extendedIdParams) {
                    if(!referencingSpec.extendedIdParams.contains(candidateParam)) {
                        extendedIdParams = CollectionUtils.add(extendedIdParams, candidateParam);
                    }
                }
            } else if(gen.isInherited(referencingSpecName)) {
                continue;
            } else {
                referencingDescr = referencingSpec.getDescr(referencingModel);
                extendedIdParams = this.extendedIdParams;
                if(!fromReferencedSpec.equals(toReferencedSpec)) {
                    for (ModelNode ref : referencingDescr.get("refs").asList()) {
                        final ModelNode featureNode = ref.get("feature");
                        if(featureNode.asString().equals(fromReferencedSpec)) {
                            featureNode.set(toReferencedSpec);
                        }
                    }
                }
            }

            if(!extendedIdParams.isEmpty()) {
                ensureParams(referencingSpecName, referencingDescr, toReferencedSpec, extendedIdParams);
            }
        }
    }

    private void ensureRef(ModelNode descr, String featureSpec) throws ProvisioningException {
        final ModelNode refsNode = descr.get("refs");
        if(refsNode.isDefined()) {
            for (ModelNode refNode : refsNode.asList()) {
                if(refNode.get("feature").asString().equals(featureSpec)) {
                    return;
                }
            }
        }
        final ModelNode refNode = new ModelNode();
        refNode.get("feature").set(featureSpec);
        refNode.get("nillable").set(true);
        refsNode.add(refNode);
    }

    private void ensureParams(String specName, ModelNode descr, String referencedSpecName, Set<String> expectedParams) {
        final ModelNode params = descr.require("params");
        final List<ModelNode> paramsList = params.asList();
        final Set<String> ownParams;
        if(paramsList.isEmpty()) {
            ownParams = Collections.emptySet();
        } else {
            ownParams = new HashSet<>(paramsList.size());
            for (ModelNode param : paramsList) {
                ownParams.add(param.get("name").asString());
            }
        }
        for(String param : expectedParams) {
            if(ownParams.contains(param)) {
                continue;
            }
            final ModelNode paramSpec = new ModelNode();
            paramSpec.get("name").set(param);
            paramSpec.get("default").set(Constants.GLN_UNDEFINED);
            params.add(paramSpec);
            gen.debug("WARN: added extra parameter %s to feature spec %s as a consequnce of merging feature spec %s from multiple models", param, specName, referencedSpecName);
            //gen.log.info("WARN: added extra parameter " + param + " to feature spec " + specName + " as a consequnce of merging feature spec " + referencedSpecName + " from multiple models");
        }
    }

    final FeatureSpecGenerator gen;
    String standaloneName;
    ModelNode standaloneDescr;
    private boolean generateStandalone;

    String profileName;
    ModelNode profileDescr;
    private boolean generateProfile;

    String domainName;
    ModelNode domainDescr;
    boolean generateDomain;

    String hostName;
    ModelNode hostDescr;
    private boolean generateHost;

    private int mergeCode;
    private int mergedModel;
    private ModelNode mergedDescr;
    private boolean generateMerged;
    private String mergedName;

    private Map<String, FeatureSpecNode> children = Collections.emptyMap();

    private Set<String> extendedIdParams = Collections.emptySet();

    FeatureSpecNode(FeatureSpecGenerator gen, int type, String name, ModelNode descr) throws ProvisioningException {
        this.gen = gen;
        switch(type) {
            case STANDALONE_MODEL:
                this.standaloneName = name;
                this.standaloneDescr = descr;
                this.generateStandalone = !gen.isInherited(name);
                gen.addSpec(name, this);
                break;
            case PROFILE_MODEL:
                setProfileDescr(name, descr);
                break;
            case DOMAIN_MODEL:
                setDomainDescr(name, descr);
                break;
            case HOST_MODEL:
                setHostDescr(name, descr);
                break;
            default:
                throw new IllegalStateException("Unexpected node type " + type);
        }
        gen.addSpec(name, this);
    }

    String getName(int model) {
        switch(model) {
            case STANDALONE_MODEL:
                return standaloneName;
            case PROFILE_MODEL:
                return profileName;
            case DOMAIN_MODEL:
                return domainName;
            case HOST_MODEL:
                return hostName;
            default:
                throw new IllegalStateException("Unexpected node type " + model);
        }
    }

    ModelNode getDescr(int model) {
        switch(model) {
            case STANDALONE_MODEL:
                return standaloneDescr;
            case PROFILE_MODEL:
                return profileDescr;
            case DOMAIN_MODEL:
                return domainDescr;
            case HOST_MODEL:
                return hostDescr;
            default:
                throw new IllegalStateException("Unexpected node type " + model);
        }
    }

    boolean isGenerate(int model) {
        switch(model) {
            case STANDALONE_MODEL:
                return generateStandalone;
            case PROFILE_MODEL:
                return generateProfile;
            case DOMAIN_MODEL:
                return generateDomain;
            case HOST_MODEL:
                return generateHost;
            default:
                throw new IllegalStateException("Unexpected node type " + model);
        }
    }

    int getModel(String name) {
        if(name.equals(standaloneName)) {
            return STANDALONE_MODEL;
        }
        if(name.equals(profileName)) {
            return PROFILE_MODEL;
        }
        if(name.equals(domainName)) {
            return DOMAIN_MODEL;
        }
        if(name.equals(hostName)) {
            return HOST_MODEL;
        }
        final StringBuilder buf = new StringBuilder();
        buf.append("Couldn't match '").append(name).append("' to the expected spec names ");
        if(standaloneName != null) {
            buf.append(standaloneName);
        }
        if(profileName != null) {
            buf.append(profileName);
        }
        if(domainName != null) {
            buf.append(domainName);
        }
        if(hostName != null) {
            buf.append(hostName);
        }
        throw new IllegalArgumentException(buf.toString());
    }

    void setProfileDescr(String name, ModelNode descr) throws ProvisioningException {
        this.profileName = name;
        this.profileDescr = descr;
        gen.addSpec(name, this);
        generateProfile = !gen.isInherited(name);

        if(standaloneName != null && identicalInAllModels(standaloneName)) {
            try {
                assertIdenticalSpecs(standaloneName, standaloneDescr, profileName, profileDescr, PROFILE_PREFIX);
            } catch (ProvisioningException e) {
                if ("Feature spec subsystem.remoting does not match the corresponding feature spec profile.subsystem.remoting"
                        .equals(e.getMessage())) {
                    gen.warn(e.getLocalizedMessage() + ": " + e.getCause().getLocalizedMessage());
                } else {
                    throw e;
                }
            }
            extendedIdParams = Collections.singleton("profile");
            mergeCode |= STANDALONE_MODEL | PROFILE_MODEL;
            mergedModel = STANDALONE_MODEL;
            generateMerged = generateStandalone;
            mergedDescr = standaloneDescr;
            mergedName = standaloneName;
        }
    }

    void setDomainDescr(String name, ModelNode descr) throws ProvisioningException {
        this.domainName = name;
        this.domainDescr = descr;
        gen.addSpec(name, this);
        generateDomain = !gen.isInherited(name);

        if(standaloneName != null && identicalInAllModels(standaloneName)) {
            assertIdenticalSpecs(standaloneName, standaloneDescr, domainName, domainDescr, DOMAIN_PREFIX);
            mergeCode |= STANDALONE_MODEL | DOMAIN_MODEL;
            mergedModel = STANDALONE_MODEL;
            generateMerged = generateStandalone;
            mergedDescr = standaloneDescr;
            mergedName = standaloneName;
        }
    }

    void setHostDescr(String name, ModelNode descr) throws ProvisioningException {
        this.hostName = name;
        this.hostDescr = descr;
        gen.addSpec(name, this);
        generateHost = !gen.isInherited(name);

        if(standaloneName != null && identicalInAllModels(standaloneName)) {
            assertIdenticalSpecs(standaloneName, standaloneDescr, hostName, hostDescr, HOST_PREFIX);
            extendedIdParams = CollectionUtils.add(extendedIdParams, "host");
            mergeCode |= STANDALONE_MODEL | HOST_MODEL;
            mergedModel = STANDALONE_MODEL;
            generateMerged = generateStandalone;
            mergedDescr = standaloneDescr;
            mergedName = standaloneName;
        }
    }

    void processChildren(int type) throws ProvisioningException {
        final ModelNode descr;
        switch(type) {
            case STANDALONE_MODEL:
                descr = standaloneDescr;
                break;
            case DOMAIN_MODEL:
                descr = domainDescr;
                break;
            case PROFILE_MODEL:
                descr = profileDescr;
                break;
            case HOST_MODEL:
                descr = hostDescr;
                break;
            default:
                throw new IllegalStateException("Unexpected node type " + type);
        }
        if (!descr.hasDefined("children")) {
            return;
        }
        for (Property childFeature : descr.get("children").asPropertyList()) {
            processChild(type, childFeature);
        }
    }

    void processChild(int type, Property child) throws ProvisioningException {
        FeatureSpecNode childNode = null;
        final String childName = child.getName();
        final ModelNode descr = child.getValue();
        switch(type) {
            case STANDALONE_MODEL:
                break;
            case PROFILE_MODEL:
                if (childName.startsWith(PROFILE_PREFIX)) {
                    childNode = children.get(childName.substring(PROFILE_PREFIX.length()));
                    if(childNode != null) {
                        childNode.setProfileDescr(childName, descr);
                    }
                }
                break;
            case DOMAIN_MODEL:
                if(childName.startsWith(DOMAIN_PREFIX)) {
                    childNode = children.get(childName.substring(DOMAIN_PREFIX.length()));
                    if(childNode != null) {
                        childNode.setDomainDescr(childName, descr);
                    }
                }
                break;
            case HOST_MODEL:
                childNode = children.get(childName.substring(HOST_PREFIX.length()));
                if(childNode != null) {
                    childNode.setHostDescr(childName, descr);
                }
                break;
            default:
                throw new IllegalStateException("Unexpected node type " + type);
        }
        if(childNode == null) {
            childNode = new FeatureSpecNode(gen, type, childName, descr);
            children = CollectionUtils.put(children, childName, childNode);
        }
        gen.addSpec(childName, childNode);
        if(descr.hasDefined("refs")) {
            for (ModelNode ref : descr.get("refs").asList()) {
                final String featureRefName = ref.get("feature").asString();
                gen.addReferencedSpec(featureRefName, childName, childNode);
            }
        }
        if (descr.hasDefined("provides")) {
            for (ModelNode capability : descr.require("provides").asList()) {
                gen.addCapProvider(getCapId(capability.asString()), childNode);
            }
        }

        childNode.processChildren(type);
    }

    void buildSpecs() throws ProvisioningException {
        mergeSpecs();
        buildSpec(0);
        buildChildSpecs(1);
    }

    private void mergeSpecs() throws ProvisioningException {
        mergeSpecs(0);
        mergeChildSpecs(1);
    }

    private void mergeSpecs(int level) throws ProvisioningException {
        if(mergeCode == 0) {
            return;
        }

        if((mergeCode & STANDALONE_MODEL) > 0) {
            updateReferencingSpecs(standaloneName, mergedName);
        }

        if((mergeCode & PROFILE_MODEL) > 0) {
            if(mergedModel != PROFILE_MODEL) {
                if(generateMerged) {
                    ensureCapPrefix(mergedDescr, "$profile.", PROFILE_MODEL | STANDALONE_MODEL);
                    ensureRef(mergedDescr, "profile");
                }
                updateReferencingSpecs(profileName, mergedName);
                profileDescr = null;
            }
        } else if((mergeCode & DOMAIN_MODEL) > 0 && mergedModel != DOMAIN_MODEL) {
            updateReferencingSpecs(domainName, mergedName);
            domainDescr = null;
        }

        if ((mergeCode & HOST_MODEL) > 0) {
            if(mergedModel == HOST_MODEL) {
                throw new IllegalStateException("The target spec is wrong");
            }
            if (generateMerged) {
                ensureRef(mergedDescr, "host");
            }
            updateReferencingSpecs(hostName, mergedName);
            hostDescr = null;
        }

        if(generateMerged) {
            boolean hasIdParams = false;
            Map<String, ModelNode> mergedParams = Collections.emptyMap();
            if (mergedDescr.hasDefined("params")) {
                mergedParams = new HashMap<>();
                for (ModelNode param : mergedDescr.get("params").asList()) {
                    mergedParams.put(param.get("name").asString(), param);
                    if(!hasIdParams && param.has("feature-id") && param.get("feature-id").asBoolean()) {
                        hasIdParams = true;
                    }
                }
            }
            ensureIdParams(mergedName, mergedDescr, mergedParams, extendedIdParams, hasIdParams);
        }
    }

    private void mergeChildSpecs(int level) throws ProvisioningException {
        if(children.isEmpty()) {
            return;
        }
        for(FeatureSpecNode child : children.values()) {
            child.mergeSpecs(level);
            child.mergeChildSpecs(level + 1);
        }
    }

    private void buildChildSpecs(int level) throws ProvisioningException {
        if(children.isEmpty()) {
            return;
        }
        for(FeatureSpecNode child : children.values()) {
            child.buildSpec(level);
            child.buildChildSpecs(level + 1);
        }
    }

    private void buildSpec(int level) throws ProvisioningException {

        if(standaloneDescr != null && generateStandalone) {
            persistSpec(standaloneName, standaloneDescr, STANDALONE_MODEL);
        }
        if(profileDescr != null && generateProfile) {
            persistSpec(profileName, profileDescr, PROFILE_MODEL);
        }
        if(domainDescr != null && generateDomain) {
            persistSpec(domainName, domainDescr, DOMAIN_MODEL);
        }
        if(hostDescr != null && generateHost) {
            persistSpec(hostName, hostDescr, HOST_MODEL);
        }
    }
}