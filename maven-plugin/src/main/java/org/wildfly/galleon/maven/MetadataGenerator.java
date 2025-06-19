/*
 * Copyright 2016-2025 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.maven.model.License;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.GalleonLayerDependency;
import org.jboss.galleon.config.ConfigItem;
import org.jboss.galleon.config.FeatureConfig;
import org.jboss.galleon.config.FeatureGroup;
import org.jboss.galleon.layout.FeaturePackDescriber;
import org.jboss.galleon.layout.FeaturePackDescription;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.layout.ProvisioningLayoutFactory;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.spec.ConfigLayerSpec;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.spec.FeatureId;
import org.jboss.galleon.spec.FeatureParameterSpec;
import org.jboss.galleon.spec.FeatureSpec;
import org.jboss.galleon.spec.PackageDependencySpec;
import org.jboss.galleon.universe.UniverseResolver;

class MetadataGenerator {

    private static final String SEPARATOR = "/";
    private static final String ATTRIBUTE_SEPARATOR = "@@@";
    private static final String GLN_UNDEFINED = "GLN_UNDEFINED";

    private static class Resource {

        ResourceAddressItem address;
        Map<String, Map<String, Resource>> children = new TreeMap<>();
        Map<String, Attribute> attributes = new TreeMap<>();

        Resource(ResourceAddressItem address) {
            this.address = address;
        }
    }

    private static class ManagementModel {

        Resource root = new Resource(new ResourceAddressItem());

        ManagementModel() {
        }

        void populate(List<ResourceOperation> ops) {
            for (ResourceOperation op : ops) {
                Resource current = root;
                for (ResourceAddressItem item : op.address) {
                    Map<String, Resource> map = current.children.get(item.type);
                    if (map == null) {
                        map = new TreeMap<>();
                        current.children.put(item.type, map);
                    }
                    Resource child = map.get(item.name);
                    if (child == null) {
                        child = new Resource(item);
                        map.put(item.name, child);
                        current = child;
                    } else {
                        current = child;
                    }
                }
                current.attributes.putAll(op.params);
            }
        }

        ObjectNode export() {
            if (root.children.isEmpty() && root.attributes.isEmpty()) {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.createObjectNode();
            } else {
                ObjectNode model = export("", root);
                return model;
            }
        }

        ObjectNode export(String radical, Resource item) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode model = mapper.createObjectNode();
            String currentAddress = radical + (radical.endsWith(SEPARATOR) ? "" : SEPARATOR) + item.address;
            if (!currentAddress.equals(SEPARATOR)) {
                model.put("_address", currentAddress);
            }
            if (!item.attributes.isEmpty()) {
                ArrayNode attributesNode = mapper.createArrayNode();
                model.putIfAbsent("attributes", attributesNode);
                for (Map.Entry<String, Attribute> entry : item.attributes.entrySet()) {
                    Attribute p = entry.getValue();
                    ObjectNode pNode = mapper.createObjectNode();
                    pNode.put("name", p.name);
                    pNode.put("value", p.value);
                    pNode.put("_address", currentAddress + ATTRIBUTE_SEPARATOR + p.name);
                    attributesNode.add(pNode);
                    //model.put(p.name, p.value);
                }
            }
            if (!item.children.isEmpty()) {
                for (String k : item.children.keySet()) {
                    ObjectNode typeNode = mapper.createObjectNode();
                    model.putIfAbsent(k, typeNode);
                    Map<String, Resource> childs = item.children.get(k);
                    for (Map.Entry<String, Resource> c : childs.entrySet()) {
                        typeNode.putIfAbsent(c.getKey(), export(currentAddress, c.getValue()));
                    }
                }
            }
            return model;
        }
    }

    private static class Attribute {

        String name;
        String value;
    }

    private static class ResourceAddressItem {

        String type;
        String name;
        boolean isNamed;

        @Override
        public String toString() {
            if (type == null) {
                return "";
            }
            if (isNamed) {
                return type + "=*";
            } else {
                return type + "=" + name;
            }
        }
    }

    private static class ResourceOperation {

        List<ResourceAddressItem> address = new ArrayList<>();
        Map<String, Attribute> params = new HashMap<>();
    }

    static class AttributeConfiguration implements Comparable<AttributeConfiguration> {

        String address;
        String attribute;
        Set<String> systemProperties = new TreeSet<>();
        Set<String> envVariables = new TreeSet<>();

        @Override
        public int compareTo(AttributeConfiguration t) {
            return address.compareTo(t.address);
        }
    }
    private final RepositorySystem repoSystem;
    private final RepositorySystemSession repoSession;
    private final List<RemoteRepository> repositories;
    private final MavenProject project;
    private final boolean addFeaturePacksDependenciesInMetadata;

    MetadataGenerator(MavenProject project,
            RepositorySystem repoSystem,
            RepositorySystemSession repoSession,
            List<RemoteRepository> repositories,
            boolean addFeaturePacksDependenciesInMetadata) {
        this.project = project;
        this.repoSystem = repoSystem;
        this.repoSession = repoSession;
        this.repositories = repositories;
        this.addFeaturePacksDependenciesInMetadata = addFeaturePacksDependenciesInMetadata;
    }

    void generateMetadata(Path featurePack, FeaturePackDescription desc, Path metadataTarget) throws Exception {
        MavenArtifactRepositoryManager repo = new MavenArtifactRepositoryManager(repoSystem, repoSession, repositories);
        UniverseResolver resolver = UniverseResolver.builder().addArtifactResolver(repo).build();
        ProvisioningLayoutFactory fact = ProvisioningLayoutFactory.getInstance(resolver);
        fact.addLocal(featurePack, false);
        ProvisioningLayout<FeaturePackLayout> pl = fact.
                newConfigLayout(featurePack, false);
        generateMetadata(desc, pl, metadataTarget);
    }

    private void generateMetadata(FeaturePackDescription desc, ProvisioningLayout<FeaturePackLayout> pl, Path metadataTarget) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fpMetadata = mapper.createObjectNode();
        ArrayNode layers = mapper.createArrayNode();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        fpMetadata.put("version", project.getVersion());
        fpMetadata.put("feature-pack-location", project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
        fpMetadata.put("description", project.getDescription());
        if (project.getLicenses() != null && !project.getLicenses().isEmpty()) {
            ArrayNode licences = mapper.createArrayNode();
            for (License licence : project.getLicenses()) {
                licences.add(licence.getName());
            }
            fpMetadata.set("license", licences);
        } else {
            fpMetadata.set("license", mapper.createArrayNode());
        }
        if (project.getUrl() != null) {
            fpMetadata.put("url", project.getUrl());
        } else {
            fpMetadata.put("url", "");
        }
        if (project.getScm() != null && project.getScm().getUrl() != null) {
            fpMetadata.put("scm", project.getScm().getUrl());
        } else {
            fpMetadata.put("scm", "");
        }
        fpMetadata.put("name", project.getName());
        Map<String, List<ConfigLayerSpec>> layerSpecs = new HashMap<>();
        if (addFeaturePacksDependenciesInMetadata) {
            for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
                Path p = layout.getDir();
                FeaturePackDescription descDep = FeaturePackDescriber.describeFeaturePack(p, "UTF-8");
                for (ConfigLayerSpec descLayer : descDep.getLayers()) {
                    List<ConfigLayerSpec> specs = layerSpecs.get(descLayer.getId().getName());
                    if (specs == null) {
                        specs = new ArrayList<>();
                        layerSpecs.put(descLayer.getId().getName(), specs);
                    }
                    specs.add(descLayer);
                }
            }
        } else {
            for (ConfigLayerSpec spec : desc.getLayers()) {
                List<ConfigLayerSpec> specs = new ArrayList<>();
                specs.add(spec);
                layerSpecs.put(spec.getName(), specs);
            }
        }
        for (Map.Entry<String, List<ConfigLayerSpec>> entry : layerSpecs.entrySet()) {
            List<ResourceOperation> ops = new ArrayList<>();
            ObjectNode layerNode = null;
            ArrayNode depsNode = null;
            boolean propertiesAdded = false;
            Map<String, AttributeConfiguration> config = new TreeMap<>();
            for (ConfigLayerSpec spec : entry.getValue()) {
                //System.out.println("***************** LAYER " + spec.getName());
                if (layerNode == null) {
                    layerNode = mapper.createObjectNode();
                    layerNode.put("name", spec.getName());
                    layers.add(layerNode);
                }
                if (spec.hasLayerDeps()) {
                    if (depsNode == null) {
                        depsNode = mapper.createArrayNode();
                    }
                    for (GalleonLayerDependency dep : spec.getLayerDeps()) {
                        ObjectNode depNode = mapper.createObjectNode();
                        depNode.put("name", dep.getName());
                        depNode.put("optional", dep.isOptional());
                        depsNode.add(depNode);
                    }
                }
                generateModelUpdates(spec.getItems(), new ArrayList<>(), pl, ops, config);
                ManagementModel m = new ManagementModel();
                m.populate(ops);
                layerNode.putIfAbsent("managementModel", m.export());
                if (!spec.getProperties().isEmpty() && !propertiesAdded) {
                    propertiesAdded = true;
                    ArrayNode propertiesNode = mapper.createArrayNode();
                    for (Map.Entry<String, String> propsEntry : spec.getProperties().entrySet()) {
                        ObjectNode propertyNode = mapper.createObjectNode();
                        propertyNode.put("name", propsEntry.getKey());
                        propertyNode.put("value", propsEntry.getValue());
                        propertiesNode.add(propertyNode);
                    }
                    layerNode.putIfAbsent("properties", propertiesNode);
                }
                if (!config.isEmpty()) {
                    ArrayNode configNode = mapper.createArrayNode();
                    layerNode.set("configuration", configNode);
                    for (Map.Entry<String, AttributeConfiguration> configEntry : config.entrySet()) {
                        ObjectNode attNode = mapper.createObjectNode();
                        attNode.put("_address", configEntry.getKey());
                        attNode.put("attribute", configEntry.getValue().attribute);
                        AttributeConfiguration attConfig = configEntry.getValue();
                        if (!attConfig.envVariables.isEmpty()) {
                            ArrayNode envNode = mapper.createArrayNode();
                            attNode.putIfAbsent("environmentVariables", envNode);
                            for (String env : attConfig.envVariables) {
                                envNode.add(env);
                            }
                        }
                        if (!attConfig.systemProperties.isEmpty()) {
                            ArrayNode propsNode = mapper.createArrayNode();
                            attNode.putIfAbsent("systemProperties", propsNode);
                            for (String prop : attConfig.systemProperties) {
                                propsNode.add(prop);
                            }
                        }
                        configNode.add(attNode);
                    }
                }
                if (spec.hasPackageDeps()) {
                    ArrayNode packagesNode = mapper.createArrayNode();
                    layerNode.putIfAbsent("packages", packagesNode);
                    if (!spec.getLocalPackageDeps().isEmpty()) {
                        for (PackageDependencySpec p : spec.getLocalPackageDeps()) {
                            packagesNode.add(p.getName());
                        }
                    }
                    for (String origin : spec.getPackageOrigins()) {
                        for (PackageDependencySpec p : spec.getExternalPackageDeps(origin)) {
                            packagesNode.add(p.getName());
                        }
                    }
                }
            }
            if (depsNode != null && layerNode != null) {
                layerNode.putIfAbsent("dependencies", depsNode);
            }
        }

        if (!layers.isEmpty()) {
            fpMetadata.putIfAbsent("layers", layers);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(metadataTarget.toFile(), fpMetadata);
    }

    private static String retrieveParamValue(List<ConfigItem> parents, String param) {
        for (int i = parents.size() - 1; i >= 0; i--) {
            ConfigItem parent = parents.get(i);
            if (parent instanceof FeatureConfig) {
                FeatureConfig pc = (FeatureConfig) parent;
                String value = pc.getParam(param);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static ResourceOperation buildModel(FeatureSpec spec, List<ConfigItem> parents, FeatureConfig config, Map<String, AttributeConfiguration> configuration) throws ProvisioningDescriptionException {
        System.out.println("FEATURE-SPEC " + spec.getName());

        FeatureAnnotation annot = spec.getAnnotation("jboss-op");
        if (annot == null) {
            return new ResourceOperation();
        }
        List<String> addr = annot.getElementAsList("addr-params");
        ResourceOperation op = new ResourceOperation();
        Set<String> ids = new HashSet<>();
        for (String a : addr) {
            FeatureParameterSpec fps = spec.getParam(a);
            ids.add(a);
            if (fps.hasDefaultValue() && !GLN_UNDEFINED.equals(fps.getDefaultValue())) {
                ResourceAddressItem ai = new ResourceAddressItem();
                ai.type = a;
                ai.name = fps.getDefaultValue();
                op.address.add(ai);
            } else {
                String value = config.getParam(a);
                if (value == null) {
                    value = retrieveParamValue(parents, a);
                    if (value == null) {
                        if (GLN_UNDEFINED.equals(fps.getDefaultValue())) {
                            continue;
                        }
                        throw new RuntimeException("Not correct parent for spec " + spec.getName() + "\nConfig is " + config + "\n Parents " + parents);
                    }
                }
                ResourceAddressItem ai = new ResourceAddressItem();
                ai.type = a;
                ai.name = value;
                // We have 1 case: subsystem.elytron.permission-set.permissions
                if (!a.equals("subsystem")) {
                    ai.isNamed = true;
                }
                op.address.add(ai);
            }
        }
        if (annot.hasElement("complex-attribute")) {
            String attribute = annot.getElement("complex-attribute");
            StringBuilder val = new StringBuilder("{");
            for (Map.Entry<String, String> entry : config.getParams().entrySet()) {
                if (!ids.contains(entry.getKey())) {
                    val.append(" " + entry.getKey() + "=" + entry.getValue() + ",");
                    addConfiguration(entry.getKey(), entry.getValue(), op, configuration);
                }
            }
            String clean = val.toString().substring(0, val.toString().length() - 1) + " }";
            Attribute p = new Attribute();
            p.name = attribute;
            p.value = clean;
            op.params.put(p.name, p);
        } else {
            for (Map.Entry<String, String> entry : config.getParams().entrySet()) {
                if (!ids.contains(entry.getKey())) {
                    Attribute p = new Attribute();
                    p.name = entry.getKey();
                    p.value = entry.getValue();
                    op.params.put(p.name, p);
                    addConfiguration(p.name, p.value, op, configuration);
                }
            }
        }
        return op;
    }

    private static void addConfiguration(String name, String value, ResourceOperation op, Map<String, AttributeConfiguration> configuration) {
        List<Set<String>> found = parseValue(value);
        if (!found.isEmpty()) {
            StringBuilder k = new StringBuilder(SEPARATOR);
            for (ResourceAddressItem a : op.address) {
                k.append(a.type);
                k.append("=");
                k.append(a.isNamed ? "*" : a.name);
                k.append(SEPARATOR);
            }

            String key = k.substring(0, k.toString().length() - 1) + ATTRIBUTE_SEPARATOR + name;
            AttributeConfiguration s = configuration.get(key);
            if (s == null) {
                s = new AttributeConfiguration();
                s.attribute = name;
                configuration.put(key, s);
            }
            s.envVariables.addAll(found.get(0));
            s.systemProperties.addAll(found.get(1));
        }
    }

    private static List<Set<String>> parseValue(String value) {
        char[] chars = value.toCharArray();
        List<Set<String>> lst = new ArrayList<>();
        Set<String> envs = new TreeSet<>();
        Set<String> props = new TreeSet<>();
        boolean expression = false;
        StringBuilder exp = null;
        boolean envVar = false;
        boolean expressionStart = false;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == ' ') {
                continue;
            }
            if (expressionStart) {
                exp = new StringBuilder();
                String radical = value.substring(i, i + 4);
                if (radical.equals("env.")) {
                    envVar = true;
                    i += 3;
                } else {
                    exp.append(c);
                }
                expressionStart = false;
                continue;
            }
            if (expression) {
                switch (c) {
                    case '}':
                    case ':':
                        expression = false;
                        if (envVar) {
                            envs.add(exp.toString());
                        } else {
                            props.add(exp.toString());
                        }
                        envVar = false;
                        break;
                    case ',':
                        expressionStart = true;
                        if (envVar) {
                            envs.add(exp.toString());
                        } else {
                            props.add(exp.toString());
                        }
                        envVar = false;
                        break;
                    default:
                        exp.append(c);
                        break;
                }
            } else {
                if (c == '$' && chars[i + 1] == '{') {
                    expression = true;
                    expressionStart = true;
                    i += 1;
                }
            }
        }
        if (!envs.isEmpty() || !props.isEmpty()) {
            lst.add(envs);
            lst.add(props);
        }
        return lst;
    }

    private static FeatureSpec getFeatureSpec(ProvisioningLayout<FeaturePackLayout> pl, String name) throws ProvisioningException {
        for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
            if (layout.hasFeatureSpec(name)) {
                return layout.loadFeatureSpec(name);
            }
        }
        return null;
    }

    private static FeatureGroup getFeatureGroup(ProvisioningLayout<FeaturePackLayout> pl, String name) throws ProvisioningException {
        for (FeaturePackLayout layout : pl.getOrderedFeaturePacks()) {
            if (layout.hasFeatureGroup(name)) {
                return layout.loadFeatureGroupSpec(name);
            }
        }
        return null;
    }

    private static void generateModelUpdates(List<ConfigItem> items, List<ConfigItem> parents, ProvisioningLayout<FeaturePackLayout> pl, List<ResourceOperation> ops, Map<String, AttributeConfiguration> config) throws ProvisioningDescriptionException, ProvisioningException {
        for (ConfigItem i : items) {
            if (i instanceof FeatureConfig) {
                FeatureConfig fc = (FeatureConfig) i;
                FeatureSpec fp = getFeatureSpec(pl, fc.getSpecId().getName());
                if (fp == null) {
                    // Can happen in tests.
                    continue;
                }
                boolean excluded = isExcluded(parents, fp, fc);
                if (!excluded) {
                    ResourceOperation op = buildModel(fp, parents, fc, config);
                    ops.add(op);
                    if (!fc.getItems().isEmpty()) {
                        parents.add(fc);
                        generateModelUpdates(fc.getItems(), parents, pl, ops, config);
                        parents.remove(parents.size() - 1);
                    }
                }
            } else {
                if (i instanceof FeatureGroup) {
                    FeatureGroup fg = (FeatureGroup) i;
                    FeatureGroup complete = getFeatureGroup(pl, fg.getName());
                    if (!complete.getItems().isEmpty()) {
                        parents.add(fg);
                        generateModelUpdates(complete.getItems(), parents, pl, ops, config);
                        parents.remove(parents.size() - 1);
                    }
                    if (!fg.getItems().isEmpty()) {
                        parents.add(fg);
                        generateModelUpdates(fg.getItems(), parents, pl, ops, config);
                        parents.remove(parents.size() - 1);
                    }
                }
            }
        }
    }

    private static boolean isExcluded(List<ConfigItem> parents, FeatureSpec fs, FeatureConfig fc) throws ProvisioningDescriptionException {
        for (int i = parents.size() - 1; i >= 0; i--) {
            ConfigItem p = parents.get(i);
            if (p instanceof FeatureGroup) {
                FeatureGroup fg = (FeatureGroup) p;
                if (fg.hasExcludedSpecs()) {
                    if (fg.getExcludedSpecs().contains(fc.getSpecId())) {
                        return true;
                    }
                }
                if (fg.hasExcludedFeatures()) {
                    for (FeatureId id : fg.getExcludedFeatures().keySet()) {
                        if (id.getSpec().getName().equals(fc.getSpecId().getName())) {
                            // Build the featureId
                            Map<String, String> idParams = new HashMap<>();
                            for (String idParam : id.getParamNames()) {
                                if (fc.getParam(idParam) != null) {
                                    idParams.put(idParam, fc.getParam(idParam));
                                } else {
                                    String value = retrieveParamValue(parents, idParam);
                                    if (value == null) {
                                        FeatureParameterSpec fparamSpec = fs.getParam(idParam);
                                        value = fparamSpec.getDefaultValue();
                                    }
                                    idParams.put(idParam, value);
                                }
                            }
                            FeatureId fid = new FeatureId(fc.getSpecId().getName(), idParams);
                            if (fid.equals(id)) {
                                System.out.println("EXCLUDED FEATURE " + id);
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
