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

package org.wildfly.galleon.plugin.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.IoUtils;


/**
 *
 * @author Alexey Loubyansky
 */
public class ForkedEmbeddedUtil {
    public interface ForkCallback {

        void forkedForEmbedded(String... args) throws ProvisioningException;

        default void forkedEmbeddedMessage(String msg) {}
    }

    public static void fork(ForkCallback callback, String... args) throws ProvisioningException {
        final Path props = storeSystemProps();
        try {
            fork(callback, props, args);
        } finally {
            IoUtils.recursiveDelete(props);
        }
    }

    public static void fork(ForkCallback callback, Path props, String... args) throws ProvisioningException {
        // prepare the classpath
        final StringBuilder cp = new StringBuilder();
        collectCpUrls(System.getProperty("java.home"), Thread.currentThread().getContextClassLoader(), cp);

        final List<String> argsList = new ArrayList<>(6 + args.length);
        argsList.add("java");
        argsList.add("-cp");
        argsList.add(cp.toString());
        argsList.add(ForkedEmbeddedUtil.class.getName());
        argsList.add(props.toString());
        argsList.add(callback.getClass().getName());
        for(String arg : args) {
            argsList.add(arg);
        }

        final Process p;
        try {
            p = new ProcessBuilder(argsList).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to start a feature spec reading process", e);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = reader.readLine();
            while (line != null) {
                callback.forkedEmbeddedMessage(line);
                line = reader.readLine();
            }
            if (p.isAlive()) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            int exitCode = p.exitValue();
            if (exitCode != 0){
                throw new ProvisioningException("Forked embedded process has failed");
            }
        } catch (IOException e) {
            throw new ProvisioningException("Forked embedded process has failed", e);
        }
    }

    public static Path storeSystemProps() throws ProvisioningException {
        final Path props;
        try {
            props = Files.createTempFile("wfgp", "sysprops");
        } catch (IOException e) {
            throw new ProvisioningException("Failed to create a tmp file", e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(props)) {
            System.getProperties().store(writer, "");
        } catch (IOException e) {
            throw new ProvisioningException(Errors.writeFile(props), e);
        }
        return props;
    }

    public static void main(String... args) {
        try{
            if(args.length < 2) {
                throw new IllegalStateException("Expected at least two arguments but got " + Arrays.asList(args));
            }

            // set system properties
            setSystemProps(args[0]);

            Class<?> cls;
            try {
                cls = Thread.currentThread().getContextClassLoader().loadClass(args[1]);
            } catch (ClassNotFoundException e) {
                throw new ProvisioningException("Failed to locate the target class " + args[1], e);
            }
        /*if(!ForkCallback.class.isAssignableFrom(cls)) {
            throw new ProvisioningException(args[1] + " does not implement " + ForkCallback.class.getName());
        }*/

            Object o;
            try {
                o = cls.newInstance();
            } catch (Exception e) {
                throw new ProvisioningException("Failed to instantiate " + args[1], e);
            }
            ((ForkCallback)o).forkedForEmbedded(args.length == 2 ? new String[0] : Arrays.copyOfRange(args, 2, args.length));

        } catch (Throwable t){
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void setSystemProps(String path) throws ProvisioningException {
        final Path props = Paths.get(path);
        if(!Files.exists(props)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(props));
        }
        final Properties tmp = new Properties();
        try(BufferedReader reader = Files.newBufferedReader(props)) {
            tmp.load(reader);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(props));
        }
        for(Map.Entry<?, ?> prop : tmp.entrySet()) {
            final String current = System.getProperty(prop.getKey().toString());
            if(current != null) {
                // do not override the default properties
                continue;
            }
            System.setProperty(prop.getKey().toString(), prop.getValue().toString());
        }
    }

    private static void collectCpUrls(String javaHome, ClassLoader cl, StringBuilder buf) {
        final ClassLoader parentCl = cl.getParent();
        if(parentCl != null) {
            collectCpUrls(javaHome, cl.getParent(), buf);
        }
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader)cl).getURLs()) {
                final String file = url.getFile();
                if(file.startsWith(javaHome)) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append(File.pathSeparatorChar);
                }
                buf.append(file);
            }
        }
    }
}
