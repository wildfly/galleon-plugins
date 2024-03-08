/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugins.test.subsystem.preview;

import org.jboss.as.subsystem.test.AbstractSubsystemBaseTest;

import java.io.IOException;

public class SubsystemTestCase extends AbstractSubsystemBaseTest {

    public SubsystemTestCase() {
        super(TestExtension.SUBSYSTEM_NAME, new TestExtension());
    }


    @Override
    protected String getSubsystemXml() throws IOException {
        return "<subsystem xmlns=\"" + TestExtension.NAMESPACE + "\">" +
                "</subsystem>";
    }

}
