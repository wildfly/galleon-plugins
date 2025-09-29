/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.doc.generator;

import java.util.ArrayList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.spec.FeatureAnnotation;

class FeatureUtils {

    static String featureToURL(String name, ModelNode featureSpec) {
        if (name.equals("server-root")) {
            return "";
        }
        FeatureAnnotation annotation = getAnnotation(featureSpec);
        // Features that we can't instantiate.
        if (annotation == null) {
            return null;
        }
        String[] tokens = name.split("\\.");
        Map<String, String> resolvedResources = new HashMap<>();
        for (int i = 0; i < tokens.length - 1; i++) {
            resolvedResources.put(tokens[i], tokens[i + 1]);
        }
        List<String> addr = annotation.getElementAsList("addr-params");
        List<String> actualAddr = new ArrayList<>();
        Map<String, String> defaults = new HashMap<>();
        for (int i = 0; i < addr.size(); i++) {
            String addrParam = addr.get(i);
            for (ModelNode p : featureSpec.get("params").asList()) {
                String pName = p.get("name").asString();
                if (addrParam.equals(pName)) {
                    if (p.has("feature-id") && p.get("feature-id").asBoolean()) {
                        if (p.has("default")) {
                            if (p.get("default").asString().equals("GLN_UNDEFINED")) {
                                // host is wrongly tagged as undefined in generated specs.
                                // Workaround for WFCORE-7372
                                if (addrParam.equals("host") && i != 0) {
                                    actualAddr.add(addrParam);
                                }
                                continue;
                            }
                            defaults.put(pName, p.get("default").asString());
                        }
                    } else {
                        // corner case, we must use the token
                        defaults.put(pName, resolvedResources.get(pName));
                    }
                    actualAddr.add(addrParam);
                }
            }
        }
        StringBuilder url = new StringBuilder();
        for (int i = 0; i < actualAddr.size(); i++) {
            String t = actualAddr.get(i);
            String val = defaults.get(t);
            url.append((url.length() != 0 ? "/" : ""));
            if (val == null) {
                url.append(t);
            } else {
                url.append(t).append("/").append(val);
            }
        }
        if (annotation.hasElement("complex-attribute")) {
            url.append(".").append(annotation.getElement("complex-attribute"));
        }
        return url.toString();
    }

    static FeatureAnnotation getAnnotation(ModelNode descr) {
        if (!descr.hasDefined("annotation")) {
            return null;
        }
        final ModelNode annotationNode = descr.require("annotation");
        final FeatureAnnotation annotation = new FeatureAnnotation("jboss-op");
        for (Property property : annotationNode.asPropertyList()) {
            annotation.setElement(property.getName(), property.getValue().asString());
        }
        return annotation;
    }

    static Set<Feature> buildFeatures(ModelNode node, List<Child> childs) {
        // A feature can be null if it has not been generated (some legacy/deprecated content)
        // or the resource is runtime-only.
        if(node == null) {
            return emptySet();
        }
        Set<Feature> set = new TreeSet<>();
        FeatureAnnotation annotation = getAnnotation(node);
        String featureName = node.get("name").asString();
        String[] address = featureName.split("\\.");
        List<String> addr = annotation.getElementAsList("addr-params");
        Map<String, String> resolvedResources = new HashMap<>();
            for (int i = 0; i < address.length - 1; i++) {
                resolvedResources.put(address[i], address[i + 1]);
            }
        Map<String, String> idParams = new TreeMap<>();
        Map<String, String> params = new TreeMap<>();
        StringBuilder xml = new StringBuilder();
        xml.append("&lt;feature spec=\"").append(featureName).append("\"&gt;\n");
        for(ModelNode p : node.get("params").asList()) {
            String pName = p.get("name").asString();
            if (p.has("feature-id") && p.get("feature-id").asBoolean()) {
                if (!p.has("default")) {
                    idParams.put(pName, "  &lt;param name=\"" + pName + "\" value=\"{resource name}\"/&gt;\n");
                } else {
                    if (p.get("default").asString().equals("GLN_UNDEFINED")) {
                        // host is wrongly tagged as undefined in generated specs.
                        // Workaround for WFCORE-7372
                        if (pName.equals("host") && !addr.get(0).equals("host")) {
                            idParams.put(pName, "  &lt;param name=\"" + pName + "\" value=\"{resource name}\"/&gt;\n");
                        }
                    }
                }
            }else {
                if(!p.has("default")) {
                    if (addr.contains(pName)) {
                        // This is a feature-id but not tagged as is.
                        String resourceName = resolvedResources.get(pName);
                        if (resourceName == null) {
                            resourceName = "{resource name}";
                        }
                        idParams.put(pName, "  &lt;param name=\"" + pName + "\" value=\"" + resourceName + "\"/&gt;\n");
                    } else {
                        params.put(pName, "  &lt;param name=\"" + pName + "\" value=\"{value}\"/&gt;\n");
                    }
                }
            }
        }
        for (Map.Entry<String, String> entry : idParams.entrySet()) {
            xml.append(entry.getValue());
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            xml.append(entry.getValue());
        }
        xml.append("&lt;feature/&gt;");
        Feature current = new Feature(featureName, xml.toString());
        set.add(current);
        if (node.has("children")) {
            ModelNode children = node.get("children");
            int featureNameLength = featureName.length();
            for(String child : children.keys()) {
                if (child.startsWith(featureName)) {
                    String cName = child.substring(featureNameLength + 1);
                    boolean found = false;
                    for (Child c : childs) {
                        if (cName.startsWith(c.name())) {
                            found = true;
                            break;
                        }
                    }
                    // Only attach features that are not attached to a child resource.
                    if (!found) {
                        set.addAll(buildFeatures(children.get(child), emptyList()));
                    }
                }
            }
        }
        return set;
    }
}
