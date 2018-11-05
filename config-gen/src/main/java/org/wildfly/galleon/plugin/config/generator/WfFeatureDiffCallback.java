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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.arguments.ArgumentValueCallbackHandler;
import org.jboss.as.cli.parsing.arguments.ArgumentValueInitialState;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.diff.ProvisionedFeatureDiffCallback;
import org.jboss.galleon.runtime.ResolvedFeatureId;
import org.jboss.galleon.state.ProvisionedFeature;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfFeatureDiffCallback implements ProvisionedFeatureDiffCallback {

    private static final String CORE_SERVICE_CONTAINER = "core-service.service-container";
    private static final String SERVER_ROOT = "server-root";
    private static final String AUTH_CONSTRAINT = "core-service.management.access.authorization.constraint.";

    @Override
    public boolean added(ProvisionedFeature added) throws ProvisioningException {
        final String specName = added.getSpecId().getName();
        if(specName.equals(CORE_SERVICE_CONTAINER) ||
                specName.startsWith(AUTH_CONSTRAINT) ||
                specName.equals(SERVER_ROOT)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean removed(ProvisionedFeature removed) throws ProvisioningException {
        final String specName = removed.getSpecId().getName();
        if(specName.equals(SERVER_ROOT)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean matches(ProvisionedFeature original, ProvisionedFeature actual) throws ProvisioningException {
        Set<String> paramNames = new HashSet<>(actual.getParamNames());
        for(String paramName : original.getParamNames()) {
            final String originalStr = original.getConfigParam(paramName);
            if(paramNames.remove(paramName)) {
                final Object originalValue = resolve(paramName, originalStr);
                final String actualStr = actual.getConfigParam(paramName);
                if (originalValue.getClass().equals(String.class) && originalValue.equals(actualStr)) {
                    // a catch for values with complex syntax that may confuse the simplified parser
                    continue;
                }
                if(originalValue.equals(resolve(paramName, actualStr))) {
                    continue;
                }
                return false;
            }
            final String specName = original.getSpecId().getName();
            if(Constants.GLN_UNDEFINED.equals(originalStr)
                    || paramName.equals("extension") && specName.startsWith("subsystem.")
                    || paramName.equals("persist-name") && specName.equals("host")) {
                continue;
            }
            if(resolve(paramName, originalStr) == EMPTY_LIST_OR_OBJ) {
                continue;
            }
            //System.out.println("* " + actual.getId());
            //System.out.println(" " + paramName + " missing " + originalStr);
            return false;
        }
        if(!paramNames.isEmpty()) {
            //StringBuilder buf = null;
            boolean matches = true;
            final String specName = original.getSpecId().getName();
            final ResolvedFeatureId fid = actual.getId();
            for(String name : paramNames) {
                final String actualValue = actual.getConfigParam(name);
                if (fid != null &&
                        name.equals("module") &&
                        specName.equals("extension") &&
                        actualValue.equals(fid.getParams().get("extension"))) {
                    continue;
                }
                matches = false;
                /*
                if(buf == null) {
                    buf = new StringBuilder();
                    buf.append(" added");
                }
                buf.append(' ').append(name).append('=').append(actualValue);
                */
            }
            if(!matches) {
                //System.out.println("* " + fid);
                //System.out.println(buf);
                return false;
            }
        }
        return true;
    }

    /**
     * The below methods are necessary to properly compare complex attribute values
     */

    private static Object resolve(String paramName, final String provisionedValue) throws ProvisioningException {
        if(provisionedValue == null ||
                provisionedValue.isEmpty() ||
                provisionedValue.length() > 2 && provisionedValue.charAt(0) == '$' && provisionedValue.charAt(1) == '{') {
            return provisionedValue;
        }
        return toJava(toDmr(paramName, provisionedValue));
    }

    private static ModelNode toDmr(String paramName, final String provisionedValue) throws ProvisioningException {
        try {
            return ModelNode.fromString(provisionedValue);
        } catch (Exception e) {
            final ArgumentValueCallbackHandler handler = new ArgumentValueCallbackHandler();
            try {
                StateParser.parse(provisionedValue, handler, ArgumentValueInitialState.INSTANCE);
            } catch (CommandFormatException e1) {
                throw new ProvisioningException("Failed to parse parameter " + paramName + " '" + provisionedValue + "'", e1);
            }
            return handler.getResult();
        }
    }

    private static final Object EMPTY_LIST_OR_OBJ = new Object();

    private static Object toJava(ModelNode node) {
        switch(node.getType()) {
            case LIST: {
                final List<ModelNode> list = node.asList();
                if(list.isEmpty()) {
                    return EMPTY_LIST_OR_OBJ;
                }
                final int size = list.size();
                if(size == 1) {
                    return Collections.singletonList(toJava(list.get(0)));
                }
                final List<Object> o = new ArrayList<>(size);
                for(ModelNode item : list) {
                    o.add(toJava(item));
                }
                return o;
            }
            case OBJECT: {
                final List<Property> list = node.asPropertyList();
                if(list.isEmpty()) {
                    return EMPTY_LIST_OR_OBJ;
                }
                final int size = list.size();
                if(size == 1) {
                    final Property prop = list.get(0);
                    return Collections.singletonMap(prop.getName(), toJava(prop.getValue()));
                }
                Map<String, Object> map = new HashMap<>(size);
                for (Property prop : list) {
                    map.put(prop.getName(), toJava(prop.getValue()));
                }
                return map;
            }
            case PROPERTY: {
                final Property prop = node.asProperty();
                return Collections.singletonMap(prop.getName(), toJava(prop.getValue()));
            }
            default: {
                return node.asString();
            }
        }
    }
}
