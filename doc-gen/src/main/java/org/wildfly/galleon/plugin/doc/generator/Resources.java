/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.util.Collections.emptyList;
import static java.util.Collections.sort;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

record Capability(String name, boolean dynamic) {

}

record Resource(String description, String storage, List<Child> children, List<Attribute> attributes) {
    public static Resource fromModelNode(final ModelNode node, Map<String, Capability> capabilities) {
        final List<Child> children = new ArrayList<>();
        if (node.hasDefined("children")) {
            for (Property property : node.get("children").asPropertyList()) {
                children.add(Child.fromProperty(property));
            }
            sort(children);
        }
        final List<Attribute> attributes = new ArrayList<Attribute>();
        if (node.hasDefined("attributes")) {
            for (Property i : node.get("attributes").asPropertyList()) {
                attributes.add(Attribute.fromProperty(i));
            }
            sort(attributes);
        }
        String storage = null;
        if (node.hasDefined("storage")) {
            storage = node.get("storage").asString("configuration");
        }
        String description = null;
        if (node.hasDefined("description")) {
            description = node.get("description").asString();
        }

        return new Resource(description, storage, children, attributes);
    }
}

record Child(String name, String description, Deprecated deprecated,
             List<Child> children) implements Comparable<Child> {
    public static Child fromProperty(final Property property) {
        String name = property.getName();
        String description = property.getValue().get("description").asString();

        final List<Child> registrations = new ArrayList<Child>();
        ModelNode modelDesc = property.getValue().get("model-description");
        if (modelDesc.isDefined()) {
            for (Property child : modelDesc.asPropertyList()) {
                if (!child.getName().equals("*")) {
                    registrations.add(new Child(child.getName(), child.getValue().get("description").asString(""), Deprecated.fromModel(child.getValue()), emptyList()));
                }
            }
        }
        sort(registrations);

        Child op = new Child(name, description, Deprecated.fromModel(property.getValue()), registrations);

        return op;
    }

    @Override
    public int compareTo(Child o) {
        return name.compareTo(o.name);
    }
}

record Deprecated(boolean deprecated, String reason, String since) {
    public static Deprecated fromModel(final ModelNode model) {
        final boolean deprecated = model.hasDefined("deprecated");
        final String reason;
        final String since;
        if (deprecated) {
            final ModelNode dep = model.get("deprecated");
            reason = dep.get("reason").asString();
            since = dep.get("since").asString();
        } else {
            reason = null;
            since = null;
        }
        return new Deprecated(deprecated, reason, since);
    }
}

record Attribute(String name, String description, String type, boolean nillable, boolean expressionsAllowed,
                 String defaultValue, String min, String max, String accessType, String storage,
                 Deprecated deprecated,
                 String unit, String restartRequired, String capabilityReference, String stability,
                 Collection<String> allowedValues) implements Comparable<Attribute> {
    public static Attribute fromProperty(final Property property) {
        String name = property.getName();
        String description = property.getValue().get("description").asString();
        String type = property.getValue().get("type").asString();
        boolean nilable = true;
        if (property.getValue().hasDefined("nillable")) {
            nilable = property.getValue().get("nillable").asBoolean();
        }
        String defaultValue = null;
        if (property.getValue().hasDefined("default")) {
            defaultValue = property.getValue().get("default").asString();
        }
        boolean expressionsAllowed = false;
        if (property.getValue().hasDefined("expressions-allowed")) {
            expressionsAllowed = property.getValue().get("expressions-allowed").asBoolean();
        }
        String min = null;
        if (property.getValue().hasDefined("min")) {
            min = property.getValue().get("min").asString();
        }
        String max = null;
        if (property.getValue().hasDefined("max")) {
            max = property.getValue().get("max").asString();
        }
        String accessType = property.getValue().get("access-type").asString();
        String storage = property.getValue().get("storage").asString();
        String unit = null;
        if (property.getValue().hasDefined("unit")) {
            unit = property.getValue().get("unit").asString();
        }
        String restartRequired = property.getValue().get("restart-required").asStringOrNull();
        String capabilityReference = property.getValue().get("capability-reference").asStringOrNull();
        String stability = property.getValue().get("stability").asStringOrNull();
        Collection<String> allowedValues = new HashSet<>();
        if (property.getValue().hasDefined("allowed")) {
            for (ModelNode allowed : property.getValue().get("allowed").asList()) {
                allowedValues.add(allowed.asString());
            }
        }
        return new Attribute(name, description, type, nilable, expressionsAllowed, defaultValue, min, max, accessType, storage, Deprecated.fromModel(property.getValue()), unit, restartRequired, capabilityReference, stability, allowedValues);
    }

    @Override
    public int compareTo(Attribute o) {
        return name.compareTo(o.name);
    }
}

record GAV(String groupId, String artifactId, String version) {
    static GAV parse(String gav) {
        String[] parts = gav.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid GAV format. Expected format: groupId:artifactId:version");
        }
        return new GAV(parts[0], parts[1], parts[2]);
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s", groupId, artifactId, version);
    }
}

record Breadcrumb(String label, String url) {
    static List<Breadcrumb> build(PathElement... path) {
        final List<Breadcrumb> crumbs = new ArrayList<>();
        crumbs.add(new Breadcrumb("home", "index.html"));
        StringBuilder currentUrl = new StringBuilder();
        for (PathElement i : path) {
            if (!currentUrl.toString().isEmpty()) {
                currentUrl.append("/");
            }
            currentUrl.append(i.getKey());
            if (!i.isWildcard()) {
                currentUrl.append("/").append(i.getValue());
            }
            final String label = i.getKey() + (i.isWildcard() ? "" : ("=" + i.getValue()));
            String url = currentUrl.toString();
            crumbs.add(new Breadcrumb(label, url + (url.isEmpty() ? "" : "/") + "index.html"));
        }
        return crumbs;
    }
}

