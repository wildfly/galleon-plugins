/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.doc.generator;

public interface SimpleLog {
    boolean isDebugEnabled();
    void debug(String msg);
    void info(String msg);

    SimpleLog SYSTEM_LOG = new SimpleLog() {
        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public void debug(String msg) {
            System.err.println(msg);
        }

        @Override
        public void info(String msg) {
            System.out.println(msg);
        }
    };
}
