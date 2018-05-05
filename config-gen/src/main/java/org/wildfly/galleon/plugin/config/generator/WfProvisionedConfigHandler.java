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

import static org.jboss.galleon.Constants.GLN_UNDEFINED;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.arguments.ArgumentValueCallbackHandler;
import org.jboss.as.cli.parsing.arguments.ArgumentValueInitialState;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.ArtifactCoords;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.plugin.ProvisionedConfigHandler;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.jboss.galleon.runtime.ResolvedFeatureSpec;
import org.jboss.galleon.runtime.ResolvedSpecId;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.state.ProvisionedConfig;
import org.jboss.galleon.state.ProvisionedFeature;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.WfConstants;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfProvisionedConfigHandler implements ProvisionedConfigHandler {

    private interface NameFilter {
        boolean accepts(String name, int position);
    }

    private static final int OP = 0;
    private static final int WRITE_ATTR = 1;
    private static final int LIST_ADD = 2;

    private static NameFilter STANDALONE_PARAM_FILTER;
    private static NameFilter getStandaloneParamFilter() {
        if(STANDALONE_PARAM_FILTER == null) {
            STANDALONE_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !(WfConstants.PROFILE.equals(name) || WfConstants.HOST.equals(name));
                }
            };
        }
        return STANDALONE_PARAM_FILTER;
    }

    private static NameFilter DOMAIN_PARAM_FILTER;
    private static NameFilter getDomainParamFilter() {
        if(DOMAIN_PARAM_FILTER == null) {
            DOMAIN_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !WfConstants.HOST.equals(name);
                }
            };
        }
        return DOMAIN_PARAM_FILTER;
    }

    private static NameFilter HOST_PARAM_FILTER;
    private static NameFilter getHostParamFilter() {
        if(HOST_PARAM_FILTER == null) {
            HOST_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !WfConstants.PROFILE.equals(name);
                }
            };
        }
        return HOST_PARAM_FILTER;
    }

    private class ManagedOp {
        String name;
        List<String> addrParams = Collections.emptyList();
        List<String> opParams = Collections.emptyList();
        int op;
        String complexAttr;

        @Override
        public String toString() {
            return "ManagedOp{name=" + name + ", addrParams=" + addrParams + ", opParams=" + opParams + ", op=" + op + '}';
        }

        private void executeOp(ProvisionedFeature feature) throws ProvisioningException {
            final ModelNode op = writeOpAddress(feature);
            if (!opParams.isEmpty()) {
                int i = 0;
                while (i < opParams.size()) {
                    final String featureParam = opParams.get(i++);
                    String value = feature.getConfigParam(featureParam);
                    if (value == null) {
                        ++i;
                        continue;
                    }
                    setOpParam(op, opParams.get(i++), value.trim().isEmpty() ? '\"' + value + '\"' : value);
                }
            }
            handleOp(op);
        }

        private ModelNode writeOpAddress(ProvisionedFeature feature) throws ProvisioningException {
            final ModelNode op = Operations.createOperation(name);
            if(addrParams.isEmpty()) {
                return op;
            }
            final ModelNode addr = Operations.getOperationAddress(op);
            int i = 0;
            while (i < addrParams.size()) {
                final String featureParam = addrParams.get(i);
                if(!paramFilter.accepts(featureParam, i)) {
                    i += 2;
                    continue;
                }
                String value = feature.getConfigParam(featureParam);
                if(value == null) {
                    throw new ProvisioningException("Address parameter " + featureParam + " of " + feature.getId() + " is null");
                }
                if(GLN_UNDEFINED.equals(value)) {
                    i += 2;
                    continue;
                }
                ++i;
                addr.add(addrParams.get(i++), value);
            }
            return op;
        }

        void toCommandLine(ProvisionedFeature feature) throws ProvisioningException {
            switch (op) {
                case OP: {
                    executeOp(feature);
                    break;
                }
                case LIST_ADD:
                case WRITE_ATTR: {
                    executeTwoArgOps(feature);
                    break;
                }
                default:
                    throw new ProvisioningException("Unexpected op " + op);
            }
        }

        private void executeTwoArgOps(ProvisionedFeature feature) throws ProvisioningDescriptionException, ProvisioningException {
            if(complexAttr == null) {
                int i = 0;
                while (i < opParams.size()) {
                    Object value = feature.getResolvedParam(opParams.get(i++));
                    if (value == null) {
                        ++i;
                        continue;
                    }
                    final ModelNode op = writeOpAddress(feature);
                    op.get(WfConstants.NAME).set(opParams.get(i++));
                    setOpParam(op, WfConstants.VALUE, value.toString());
                    handleOp(op);
                }
                return;
            }
            final ModelNode op = writeOpAddress(feature);
            op.get(WfConstants.NAME).set(complexAttr);
            final ModelNode attrValue = new ModelNode();
            int i = 0;
            while (i < opParams.size()) {
                Object value = feature.getResolvedParam(opParams.get(i++));
                if (value == null) {
                    ++i;
                    continue;
                }
                setOpParam(attrValue, opParams.get(i++), value.toString());
            }
            op.get(WfConstants.VALUE).set(attrValue);
            handleOp(op);
        }
    }

    private List<ManagedOp> createWriteAttributeManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation) throws ProvisioningException {
        return createTwoArgOp(spec, annotation, WfConstants.WRITE_ATTRIBUTE, WRITE_ATTR);
    }

    private List<ManagedOp> createTwoArgOp(ResolvedFeatureSpec spec, FeatureAnnotation annotation, String name, int op) throws ProvisioningException {
        String elemValue = annotation.getElement(WfConstants.ADDR_PARAMS);
        if (elemValue == null) {
            throw new ProvisioningException("Required element " + WfConstants.ADDR_PARAMS + " is missing for " + spec.getId());
        }
        List<String> addrParams = Collections.emptyList();
        if (!"server-root".equals(elemValue)) {
            try {
                addrParams = parseList(annotation.getElementAsList(WfConstants.ADDR_PARAMS), annotation.getElementAsList(WfConstants.ADDR_PARAMS_MAPPING));
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw an empty parameter name in annotation " + WfConstants.ADDR_PARAMS + "="
                        + elemValue + " of " + spec.getId());
            }
        }
        List<ManagedOp> operations;
        elemValue = annotation.getElement(WfConstants.OP_PARAMS, GLN_UNDEFINED);
        final String complexAttr = annotation.getElement("complex-attribute");
        if (GLN_UNDEFINED.equals(elemValue)) {
            if(!spec.hasParams()) {
                throw new ProvisioningDescriptionException(WfConstants.OP_PARAMS + " element of "
                        + name + " annotation of " + spec.getId()
                        + " accepts only one parameter: " + annotation);
            }
            final Set<String> allParams = spec.getParamNames();
            final int opParams = allParams.size() - addrParams.size() / 2;
            if (opParams == 0) {
                throw new ProvisioningDescriptionException(WfConstants.OP_PARAMS + " element of " + name
                        + " annotation of " + spec.getId() + " accepts only one parameter: " + annotation);
            }
            if (complexAttr == null) {
                operations = new ArrayList<>(allParams.size());
                for (String paramName : allParams) {
                    boolean inAddr = false;
                    int j = 0;
                    while (!inAddr && j < addrParams.size()) {
                        if (addrParams.get(j).equals(paramName)) {
                            inAddr = true;
                        }
                        j += 2;
                    }
                    if (!inAddr) {
                        if (paramFilter.accepts(paramName, j)) {
                            final ManagedOp mop = new ManagedOp();
                            mop.name = name;
                            mop.op = op;
                            mop.addrParams = addrParams;
                            mop.opParams = new ArrayList<>(2);
                            mop.opParams.add(paramName);
                            mop.opParams.add(paramName);
                            operations.add(mop);
                        }
                    }
                }
            } else {
                final ManagedOp mop = new ManagedOp();
                mop.name = name;
                mop.op = op;
                mop.complexAttr = complexAttr;
                mop.addrParams = addrParams;
                mop.opParams = new ArrayList<>(allParams.size());
                for (String paramName : allParams) {
                    boolean inAddr = false;
                    int j = 0;
                    while (!inAddr && j < addrParams.size()) {
                        if (addrParams.get(j).equals(paramName)) {
                            inAddr = true;
                        }
                        j += 2;
                    }
                    if (!inAddr) {
                        if (paramFilter.accepts(paramName, j)) {
                            mop.opParams.add(paramName);
                            mop.opParams.add(paramName);
                        }
                    }
                }
                operations = Collections.singletonList(mop);
            }
        } else {
            final List<String> params;
            try {
                params = parseList(annotation.getElementAsList(WfConstants.OP_PARAMS), annotation.getElementAsList(WfConstants.OP_PARAMS_MAPPING));
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw empty parameter name in note " + WfConstants.ADDR_PARAMS
                        + "=" + elemValue + " of " + spec.getId());
            }
            if(complexAttr == null) {
                operations = new ArrayList<>(params.size());
                for (int i = 0; i < params.size(); i++) {
                    if (i % 2 == 0) {
                        final ManagedOp mop = new ManagedOp();
                        mop.name = name;
                        mop.op = op;
                        mop.addrParams = addrParams;
                        mop.opParams = new ArrayList<>(2);
                        mop.opParams.add(params.get(i));
                        mop.opParams.add(params.get(i + 1));
                        operations.add(mop);
                    }
                }
            } else {
                final ManagedOp mop = new ManagedOp();
                mop.name = name;
                mop.op = op;
                mop.addrParams = addrParams;
                mop.complexAttr = complexAttr;
                mop.opParams = new ArrayList<>(params.size()*2);
                for (int i = 0; i < params.size(); i++) {
                    if (i % 2 == 0) {
                        mop.opParams.add(params.get(i));
                        mop.opParams.add(params.get(i + 1));
                    }
                }
                operations = Collections.singletonList(mop);
            }
        }
        return operations;
    }

    private List<ManagedOp> createAddListManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation) throws ProvisioningException {
        return createTwoArgOp(spec, annotation, WfConstants.LIST_ADD, LIST_ADD);
    }

    private List<ManagedOp> createManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation, String name, int operation) throws ProvisioningException {
        final ManagedOp mop = new ManagedOp();
        mop.name = name;
        mop.op = operation;

        String elemValue = annotation.getElement(WfConstants.ADDR_PARAMS);
        if (elemValue == null) {
            throw new ProvisioningException("Required element " + WfConstants.ADDR_PARAMS + " is missing for " + spec.getId());
        }

        try {
            mop.addrParams = parseList(annotation.getElementAsList(WfConstants.ADDR_PARAMS), annotation.getElementAsList(WfConstants.ADDR_PARAMS_MAPPING));
        } catch (ProvisioningDescriptionException e) {
            throw new ProvisioningDescriptionException("Saw an empty parameter name in annotation " + WfConstants.ADDR_PARAMS + "="
                    + elemValue + " of " + spec.getId());
        }
        if (mop.addrParams == null) {
            return Collections.emptyList();
        }

        elemValue = annotation.getElement(WfConstants.OP_PARAMS, GLN_UNDEFINED);
        if (GLN_UNDEFINED.equals(elemValue)) {
            if (spec.hasParams()) {
                final Set<String> allParams = spec.getParamNames();
                final int opParams = allParams.size() - mop.addrParams.size() / 2;
                if (opParams == 0) {
                    mop.opParams = Collections.emptyList();
                } else {
                    mop.opParams = new ArrayList<>(opParams * 2);
                    for (String paramName : allParams) {
                        boolean inAddr = false;
                        int j = 0;
                        while (!inAddr && j < mop.addrParams.size()) {
                            if (mop.addrParams.get(j).equals(paramName)) {
                                inAddr = true;
                            }
                            j += 2;
                        }
                        if (!inAddr) {
                            if (paramFilter.accepts(paramName, j)) {
                                mop.opParams.add(paramName);
                                mop.opParams.add(paramName);
                            }
                        }
                    }
                }
            } else {
                mop.opParams = Collections.emptyList();
            }
        } else {
            try {
                mop.opParams = parseList(annotation.getElementAsList(WfConstants.OP_PARAMS, GLN_UNDEFINED), annotation.getElementAsList(WfConstants.OP_PARAMS_MAPPING));
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw empty parameter name in note " + WfConstants.ADDR_PARAMS
                        + "=" + elemValue + " of " + spec.getId());
            }
        }
        return Collections.singletonList(mop);
    }

    private final MessageWriter messageWriter;
    private final WfConfigGenerator configGen;

    private final Map<ResolvedSpecId, List<ManagedOp>> specOps = new HashMap<>();
    private List<ManagedOp> ops = Collections.emptyList();
    private NameFilter paramFilter;

    private ModelNode composite;
    private BufferedWriter writer;

    public WfProvisionedConfigHandler(ProvisioningRuntime runtime, WfConfigGenerator configGen) throws ProvisioningException {
        this.messageWriter = runtime.getMessageWriter();
        this.configGen = configGen;
    }

    @Override
    public void prepare(ProvisionedConfig config) throws ProvisioningException {
        if(WfConstants.STANDALONE.equals(config.getModel())) {
            configGen.startServer(getEmbeddedArgs(config));
            paramFilter = getStandaloneParamFilter();
        } else if(WfConstants.DOMAIN.equals(config.getModel())) {
            configGen.startHc(getEmbeddedArgs(config));
            configGen.execute(Operations.createAddOperation(Operations.createAddress("host", "tmp")));
            paramFilter = getDomainParamFilter();
        } else if (WfConstants.HOST.equals(config.getModel())) {
            configGen.startHc(getEmbeddedArgs(config));
            paramFilter = getHostParamFilter();
        } else {
            throw new ProvisioningException("Unsupported config model " + config.getModel());
        }

        //logScript(config);
    }

    @Override
    public void nextFeaturePack(ArtifactCoords.Gav fpGav) throws ProvisioningException {
        messageWriter.verbose("  %s", fpGav);
    }

    @Override
    public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
        messageWriter.verbose("    SPEC %s", spec.getName());
        if(!spec.hasAnnotations()) {
            ops = Collections.emptyList();
            return;
        }

        ops = specOps.get(spec.getId());
        if(ops != null) {
            return;
        }

        ops = Collections.emptyList();
        for (FeatureAnnotation annotation : spec.getAnnotations()) {
            if(!annotation.getName().equals(WfConstants.JBOSS_OP)) {
                continue;
            }
            ops = CollectionUtils.addAll(ops, nextAnnotation(spec, annotation));
        }
        specOps.put(spec.getId(), ops);
    }

    private List<ManagedOp> nextAnnotation(final ResolvedFeatureSpec spec, final FeatureAnnotation annotation) throws ProvisioningException {
        final String name = annotation.getElement(WfConstants.NAME);
        switch (name) {
            case WfConstants.WRITE_ATTRIBUTE:
                return createWriteAttributeManagedOperation(spec, annotation);
            case WfConstants.LIST_ADD:
                return createAddListManagedOperation(spec, annotation);
            default:
                return createManagedOperation(spec, annotation, name, OP);
        }
    }

    @Override
    public void nextFeature(ProvisionedFeature feature) throws ProvisioningException {
        if (ops.isEmpty()) {
            messageWriter.verbose("      %s", feature.getResolvedParams());
            return;
        }
        for(ManagedOp op : ops) {
            op.toCommandLine(feature);
        }
    }

    @Override
    public void startBatch() throws ProvisioningException {
        messageWriter.verbose("      START BATCH");
        //logToFile("batch");
        composite = Operations.createCompositeOperation();
    }

    @Override
    public void endBatch() throws ProvisioningException {
        messageWriter.verbose("      END BATCH");
        //logToFile("run-batch");
        try {
            configGen.execute(composite);
        } catch(Throwable t) {
            //closeLogFile();
            if(t instanceof ProvisioningException) {
                throw t;
            }
            throw new ProvisioningException("Failed to execute operation " + composite, t);
        }
        composite = null;
    }

    @Override
    public void done() throws ProvisioningException {
        //closeLogFile();
        configGen.stopEmbedded();
    }

    private String[] getEmbeddedArgs(ProvisionedConfig config) throws ProvisioningException {
        final List<String> embeddedArgs = new ArrayList<>(config.getProperties().size());
        final String configNameProp;
        if(WfConstants.STANDALONE.equals(config.getModel())) {
            configNameProp = WfConstants.EMBEDDED_ARG_SERVER_CONFIG;
        } else if(WfConstants.DOMAIN.equals(config.getModel())) {
            configNameProp = WfConstants.EMBEDDED_ARG_DOMAIN_CONFIG;
        } else if(WfConstants.HOST.equals(config.getModel())) {
            configNameProp = WfConstants.EMBEDDED_ARG_HOST_CONFIG;
        } else {
            throw new ProvisioningException("Unexpected config model " + config.getModel());
        }
        boolean explicitlyNamed = false;
        for(Map.Entry<String, String> prop : config.getProperties().entrySet()) {
            if(!prop.getKey().startsWith("--")) {
                continue;
            }
            embeddedArgs.add(prop.getKey());
            if(!prop.getValue().isEmpty()) {
                embeddedArgs.add(prop.getValue());
            }
            if(!explicitlyNamed) {
                explicitlyNamed = prop.getValue().equals(configNameProp);
            }
        }
        if(!explicitlyNamed) {
            embeddedArgs.add(configNameProp);
            embeddedArgs.add(config.getName());
        }
        return embeddedArgs.toArray(new String[embeddedArgs.size()]);
    }

    private void handleOp(ModelNode op) throws ProvisioningException {
        //logOp(op);

        if(composite != null) {
            composite.get(WfConstants.STEPS).add(op);
        } else {
            try {
                configGen.execute(op);
            } catch(ProvisioningException e) {
                //closeLogFile();
                throw e;
            }
        }
    }

    private void setOpParam(ModelNode op, String name, String value) throws ProvisioningException {
        ModelNode toSet = null;
        try {
            toSet = ModelNode.fromString(value);
        } catch (Exception e) {
            final ArgumentValueCallbackHandler handler = new ArgumentValueCallbackHandler();
            try {
                StateParser.parse(value, handler, ArgumentValueInitialState.INSTANCE);
            } catch (CommandFormatException e1) {
                throw new ProvisioningException("Failed to parse parameter " + name + " '" + value + "'", e1);
            }
            toSet = handler.getResult();
        }
        op.get(name).set(toSet);
    }

    static List<String> parseList(List<String> params, List<String> mappings) throws ProvisioningDescriptionException {
        if (params == null || params.isEmpty()) {
            return Collections.emptyList();
        }
        if (params.size() != mappings.size() && mappings.size() > 0) {
            throw new ProvisioningDescriptionException("Mappings and params don't match");
        }
        List<String> list = new ArrayList<>(2*params.size());
        for (int i = 0; i < params.size(); i++) {
            list.add(params.get(i));
            list.add(mappings.isEmpty() ? params.get(i) : mappings.get(i));
        }
        return list;
    }

    private void logScript(ProvisionedConfig config) {
        try {
            writer = Files.newBufferedWriter(Paths.get(System.getProperty("user.home")).resolve("galleon-scripts").resolve("script-" + config.getName()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void closeLogFile() {
        try {
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void logToFile(String line) {
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void logOp(ModelNode op) {
        try {
            final StringBuilder buf = new StringBuilder();
            final List<Property> addrList = op.get("address").asPropertyList();
            if(!addrList.isEmpty()) {
                for(Property addr : addrList) {
                    buf.append('/').append(addr.getName()).append('=').append(addr.getValue().asString());
                }
            }
            buf.append(':');
            buf.append(op.get("operation").asString());
            final List<Property> params = op.asPropertyList();
            if(params.size() > 2) {
                buf.append('(');
                boolean comma = false;
                for(Property param : params) {
                    final String paramName = param.getName();
                    if(paramName.equals("address") || paramName.equals("operation")) {
                        continue;
                    }
                    if(comma) {
                        buf.append(',');
                    } else {
                        comma = true;
                    }
                    buf.append(param.getName()).append('=').append(param.getValue().asString());
                }
                buf.append(')');
            }
            writer.write(buf.toString());
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
