/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.server;

public class ConfigGeneratorException extends Exception {

   public ConfigGeneratorException(String msg, Throwable cause) {
      super(msg, cause);
   }

   public ConfigGeneratorException(String msg) {
      super(msg);
   }

   public ConfigGeneratorException(Exception cause) {
      super(cause);
   }
}
