/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.util.Collections.emptyList;
import static java.util.Collections.sort;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

record Capability(String name, boolean dynamic) {

}

record Resource(String description, String storage, List<Child> children, List<Attribute> attributes, List<Operation> operations) {
    public static Resource fromModelNode(final ModelNode node, Map<String, Capability> capabilities) {
        List<Child> children = emptyList();
        if (node.hasDefined("children")) {
            children = node.get("children").asPropertyList()
                    .stream()
                    .map(Child::fromProperty)
                    .sorted()
                    .toList();
        }
        List<Attribute> attributes = emptyList();
        if (node.hasDefined("attributes")) {
            attributes = node.get("attributes").asPropertyList()
                    .stream()
                    .map(Attribute::fromProperty)
                    .sorted()
                    .toList();
        }
        List<Operation> operations = emptyList();
        if (node.hasDefined("operations")) {
            operations = node.get("operations").asPropertyList()
                    .stream()
                    .map(Operation::fromProperty)
                    .sorted()
                    .toList();
        }
        String storage = null;
        if (node.hasDefined("storage")) {
            storage = node.get("storage").asString("configuration");
        }
        String description = null;
        if (node.hasDefined("description")) {
            description = node.get("description").asString();
        }

        return new Resource(description, storage, children, attributes, operations);
    }
}

record Child(String name, String description, Deprecation deprecation,
             List<Child> children) implements Comparable<Child> {
    public static Child fromProperty(final Property property) {
        String name = property.getName();
        String description = property.getValue().get("description").asString();

        final List<Child> registrations = new ArrayList<>();
        ModelNode modelDesc = property.getValue().get("model-description");
        if (modelDesc.isDefined()) {
            for (Property child : modelDesc.asPropertyList()) {
                if (!child.getName().equals("*")) {
                    registrations.add(new Child(child.getName(), child.getValue().get("description").asString(""), Deprecation.fromModel(child.getValue()), emptyList()));
                }
            }
        }
        sort(registrations);

        return new Child(name, description, Deprecation.fromModel(property.getValue()), registrations);
    }

    @Override
    public int compareTo(Child o) {
        return name.compareTo(o.name);
    }
}

record Deprecation(boolean deprecated, String reason, String since) {
    public static Deprecation fromModel(final ModelNode model) {
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
        return new Deprecation(deprecated, reason, since);
    }
}

record Attribute(String name, String description, String type, boolean nillable, boolean expressionsAllowed,
                 String defaultValue, String min, String max, String accessType, String storage,
                 Deprecation deprecation,
                 String unit, String restartRequired, String capabilityReference, String stability,
                 Collection<String> allowedValues, String jsonRepresentation) implements Comparable<Attribute> {
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
        Collection<String> allowedValues = Collections.emptySet();
        if (property.getValue().hasDefined("allowed")) {
            allowedValues = property.getValue().get("allowed").asList()
                    .stream()
                    .map(ModelNode::asString)
                    .collect(Collectors.toSet());
        }
        String jsonRepresentation = property.getValue().toJSONString(false);
        return new Attribute(name, description, type, nilable, expressionsAllowed, defaultValue, min, max, accessType, storage, Deprecation.fromModel(property.getValue()), unit, restartRequired, capabilityReference, stability, allowedValues, jsonRepresentation);
    }

    @Override
    public int compareTo(Attribute o) {
        return name.compareTo(o.name);
    }
}

record Operation(String name, String description, boolean readOnly, boolean runtimeOnly, String stability,
                 Deprecation deprecation, List<Parameter> parameters, Reply reply, String jsonRepresentation) implements Comparable<Operation> {
    public static Operation fromProperty(final Property property) {
        String name = property.getName();
        ModelNode model = property.getValue();
        String description = model.get("description").asString();
        boolean readOnly = false;
        if (model.hasDefined("read-only")) {
            readOnly = model.get("read-only").asBoolean();
        }
        boolean runtimeOnly = false;
        if (model.hasDefined("runtime-only")) {
            runtimeOnly = model.get("runtime-only").asBoolean();
        }
        String stability = model.get("stability").asStringOrNull();
        String jsonRepresentation = model.toJSONString(false);
        List<Parameter> parameters = emptyList();
        if (model.hasDefined("request-properties")) {
            parameters = model.get("request-properties").asPropertyList()
                    .stream()
                    .map(Parameter::fromProperty)
                    .toList();
        }
        Reply reply = null;
        if (model.hasDefined("reply-properties")) {
            reply = Reply.fromModelNode(model.get("reply-properties"));
        }

        return new Operation(name, description, readOnly, runtimeOnly, stability, Deprecation.fromModel(model), parameters, reply, jsonRepresentation);
    }

    @Override
    public int compareTo(Operation o) {
        return name.compareTo(o.name);
    }

    record Parameter(String name, String type, String description, boolean required, boolean nillable, boolean expressionsAllowed, String defaultValue) {
        public static Parameter fromProperty(final Property property) {
            String name = property.getName();
            ModelNode value = property.getValue();
            String type = value.get("type").asString();
            String description = value.get("description").asStringOrNull();
            boolean required = value.get("required").asBoolean();
            boolean nillable = value.get("nillable").asBoolean();
            boolean expressionsAllowed = value.get("expressions-allowed").asBoolean();
            String defaultValue = value.get("default").asStringOrNull();

            return new Parameter(name, type, description, required, nillable, expressionsAllowed, defaultValue);
        }
    }

    record Reply(String type, String valueType, String description) {
        public static Reply fromModelNode(final ModelNode model) {
            if (!model.hasDefined("type")) {
                return null;
            }
            String returnType = model.get("type").asString();
            String returnDescription = model.get("description").asString("");
            ModelNode returnValueType = model.get("value-type");
            StringWriter writer = new StringWriter();
            returnValueType.writeString(new PrintWriter(writer), false);
            String valueType = writer.toString();
            return new Reply(returnType, returnValueType.isDefined() ? valueType : null, returnDescription);
        }
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

