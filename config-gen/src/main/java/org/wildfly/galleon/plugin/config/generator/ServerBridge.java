/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.galleon.plugin.config.generator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.server.ConfigGeneratorException;

public class ServerBridge {

    private static final String ModelControllerClient = "org.jboss.as.controller.client.ModelControllerClient";
    private static final String EmbeddedManagedProcess = "org.wildfly.core.embedded.EmbeddedManagedProcess";
    private static final String EmbeddedProcessFactory = "org.wildfly.core.embedded.EmbeddedProcessFactory";
    private static final String ModelNode = "org.jboss.dmr.ModelNode";
    private static final String Operations = "org.jboss.as.controller.client.helpers.Operations";
    private static final String ClientConstants = "org.jboss.as.controller.client.helpers.ClientConstants";

    private static final String createHostController = "createHostController";
    private static final String createStandaloneServer = "createStandaloneServer";
    private static final String start = "start";
    private static final String getModelControllerClient = "getModelControllerClient";
    private static final String execute = "execute";
    private static final String fromJSONString = "fromJSONString";
    private static final String get = "get";
    private static final String set = "set";
    private static final String add = "add";
    private static final String isSuccessfulOutcome = "isSuccessfulOutcome";
    private static final String getFailureDescription = "getFailureDescription";
    private static final String createCompositeOperation = "createCompositeOperation";
    private static final String getProcessState = "getProcessState";
    private static final String close = "close";
    private static final String stop = "stop";
    private static final String asString = "asString";
    private static final String READ_ATTRIBUTE_OPERATION = "READ_ATTRIBUTE_OPERATION";
    private static final String NAME = "NAME";
    private static final String OP = "OP";
    private static final String RESULT = "RESULT";

    private static Method createHostControllerMethod;
    private static Method createStandaloneServerMethod;
    private static Method startMethod;
    private static Method getModelControllerClientMethod;
    private static Method executeMethod;
    private static Method fromJSONStringMethod;
    private static Method getMethod;
    private static Method setMethod;
    private static Method addMethod;
    private static Method isSuccessfulOutcomeMethod;
    private static Method getFailureDescriptionMethod;
    private static Method createCompositeOperationMethod;
    private static Method getProcessStateMethod;
    private static Method closeMethod;
    private static Method stopMethod;
    private static Method asStringMethod;
    private static Constructor dmrNewInstance;

    static String READ_ATTRIBUTE_OPERATION_FIELD_VALUE;
    static String NAME_FIELD_VALUE;
    static String OP_FIELD_VALUE;
    static String RESULT_FIELD_VALUE;

    private static ServerBridge INSTANCE;

    private ServerBridge() {
    }

    static synchronized ServerBridge get(ClassLoader cl) throws ConfigGeneratorException {
        if (INSTANCE == null) {
            initializeEmbedded(cl);
            INSTANCE = new ServerBridge();
        }
        return INSTANCE;
    }

    private static void initializeEmbedded(ClassLoader loader) throws ConfigGeneratorException {
        try {
            Class<?> EmbeddedManagedProcessClass = Class.forName(EmbeddedManagedProcess, true, loader);
            Class<?> EmbeddedProcessFactoryClass = Class.forName(EmbeddedProcessFactory, true, loader);
            Class<?> ModelControllerClientClass = Class.forName(ModelControllerClient, true, loader);
            Class<?> ModelNodeClass = Class.forName(ModelNode, true, loader);
            Class<?> OperationsClass = Class.forName(Operations, true, loader);
            Class<?> ClientConstantsClass = Class.forName(ClientConstants, true, loader);
            dmrNewInstance = ModelNodeClass.getConstructor();
            createHostControllerMethod = EmbeddedProcessFactoryClass.getMethod(createHostController,
                    String.class,
                    String.class,
                    String[].class,
                    String[].class);
            createStandaloneServerMethod = EmbeddedProcessFactoryClass.getMethod(createStandaloneServer,
                    String.class,
                    String.class,
                    String[].class,
                    String[].class);
            startMethod = EmbeddedManagedProcessClass.getMethod(start);
            getModelControllerClientMethod = EmbeddedManagedProcessClass.getMethod(getModelControllerClient);
            executeMethod = ModelControllerClientClass.getMethod(execute, ModelNodeClass);
            fromJSONStringMethod = ModelNodeClass.getMethod(fromJSONString, String.class);
            getMethod = ModelNodeClass.getMethod(get, String.class);
            setMethod = ModelNodeClass.getMethod(set, String.class);
            addMethod = ModelNodeClass.getMethod(add, ModelNodeClass);
            isSuccessfulOutcomeMethod = OperationsClass.getMethod(isSuccessfulOutcome, ModelNodeClass);
            getFailureDescriptionMethod = OperationsClass.getMethod(getFailureDescription, ModelNodeClass);
            createCompositeOperationMethod = OperationsClass.getMethod(createCompositeOperation);
            getProcessStateMethod = EmbeddedManagedProcessClass.getMethod(getProcessState);
            closeMethod = ModelControllerClientClass.getMethod(close);
            stopMethod = EmbeddedManagedProcessClass.getMethod(stop);
            asStringMethod = ModelNodeClass.getMethod(asString);

            READ_ATTRIBUTE_OPERATION_FIELD_VALUE = (String) ClientConstantsClass.getField(READ_ATTRIBUTE_OPERATION).get(null);
            NAME_FIELD_VALUE = (String) ClientConstantsClass.getField(NAME).get(null);
            OP_FIELD_VALUE = (String) ClientConstantsClass.getField(OP).get(null);
            RESULT_FIELD_VALUE = (String) ClientConstantsClass.getField(RESULT).get(null);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    void dmr_steps_add(Object composite, String json) throws ConfigGeneratorException {
        try {
            Object steps = getMethod.invoke(composite, WfConstants.STEPS);
            Object op = fromJSONStringMethod.invoke(null, json);
            addMethod.invoke(steps, op);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object dmr_fromJSON(String json) throws ConfigGeneratorException {
        try {
            return fromJSONStringMethod.invoke(null, json);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object mcc_execute(Object mcc, Object op) throws ConfigGeneratorException {
        try {
            return executeMethod.invoke(mcc, op);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    void mcc_close(Object mcc) throws ConfigGeneratorException {
        try {
            closeMethod.invoke(mcc);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Boolean dmr_isSuccessful(Object response) throws ConfigGeneratorException {
        try {
            return (Boolean) isSuccessfulOutcomeMethod.invoke(null, response);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    String dmr_getFailureDescription(Object response) throws ConfigGeneratorException {
        try {
            Object obj = getFailureDescriptionMethod.invoke(null, response);
            return dmr_asString(obj);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object dmr_createCompositeOperation() throws ConfigGeneratorException {
        try {
            return createCompositeOperationMethod.invoke(null);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    String dmr_asString(Object dmr) throws ConfigGeneratorException {
        try {
            return (String) asStringMethod.invoke(dmr);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object dmr_get(Object dmr, String field) throws ConfigGeneratorException {
        try {
            return getMethod.invoke(dmr, field);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object dmr_set(Object dmr, String field) throws ConfigGeneratorException {
        try {
            return setMethod.invoke(dmr, field);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object dmr_newInstance() throws ConfigGeneratorException {
        try {
            return dmrNewInstance.newInstance();
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object embed_createHostController(String jbossHome, String[] args) throws ConfigGeneratorException {
        try {
            return createHostControllerMethod.invoke(null, jbossHome, null, null, args);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object embed_createStandalone(String jbossHome, String[] args) throws ConfigGeneratorException {
        try {
            return createStandaloneServerMethod.invoke(null, jbossHome, null, null, args);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    void embed_start(Object embeddedProcess) throws ConfigGeneratorException {
        try {
            startMethod.invoke(embeddedProcess);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    void embed_stop(Object embeddedProcess) throws ConfigGeneratorException {
        try {
            stopMethod.invoke(embeddedProcess);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    Object embed_getModelControllerClient(Object embeddedProcess) throws ConfigGeneratorException {
        try {
            return getModelControllerClientMethod.invoke(embeddedProcess);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }

    String embed_getProcessState(Object embeddedProcess) throws ConfigGeneratorException {
        try {
            return (String) getProcessStateMethod.invoke(embeddedProcess);
        } catch (Exception ex) {
            throw new ConfigGeneratorException(ex);
        }
    }
}
