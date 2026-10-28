/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.server;

public interface ForkCallback {

   void forkedForEmbedded(String... args) throws ConfigGeneratorException;

   default void forkedEmbeddedMessage(String msg) {
   }

   default void forkedEmbeddedDone(String... args) throws ConfigGeneratorException {
   }
}
