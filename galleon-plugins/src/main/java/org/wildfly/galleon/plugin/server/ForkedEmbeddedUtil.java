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
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.CollectionUtils;
import org.jboss.galleon.util.IoUtils;


/**
 *
 * @author Alexey Loubyansky
 */
public class ForkedEmbeddedUtil {

    public static final String FORKED_EMBEDDED_ERROR_START = "Forked embedded process has failed with the following error:";

    private static int javaVersion = -1;
    private static String javaHome;
    private static String javaCmd;

    private static int getJavaVersion() {
        if (javaVersion < 0) {
            try {
                String vmVersionStr = System.getProperty("java.specification.version", null);
                Matcher matcher = Pattern.compile("^(?:1\\.)?(\\d+)$").matcher(vmVersionStr); // match 1.<number> or <number>
                if (matcher.find()) {
                    javaVersion = Integer.valueOf(matcher.group(1));
                } else {
                    throw new RuntimeException("Unknown version of jvm " + vmVersionStr);
                }
            } catch (Exception e) {
                javaVersion = 8;
            }
        }
        return javaVersion;
    }

    private static String getJavaHome() {
        return javaHome == null ? javaHome = System.getProperty("java.home") : javaHome;
    }

    private static String getJavaCmd() {
        return javaCmd == null ? javaCmd = Paths.get(getJavaHome()).resolve("bin").resolve("java").toString() : javaCmd;
    }

    public static void fork(ForkCallback callback, String... args) throws ProvisioningException {
        final Path props = storeSystemProps();
        try {
            fork(callback, props, args);
            callback.forkedEmbeddedDone(args);
        } catch (ConfigGeneratorException e) {
            throw new ProvisioningException(e);
        } finally {
            IoUtils.recursiveDelete(props);
        }
    }

    public static void fork(ForkCallback callback, Path props, String... args) throws ProvisioningException {
        // prepare the classpath
        final StringBuilder cp = new StringBuilder();
        collectCpUrls(getJavaHome(), Thread.currentThread().getContextClassLoader(), cp);

        final List<String> argsList = new ArrayList<>(8 + args.length);
        argsList.add(getJavaCmd());
        argsList.add("-server");
        if (getJavaVersion() >= 11) {
            argsList.add("--add-modules=java.se");
        }
        argsList.add("-cp");
        argsList.add(cp.toString());
        argsList.add(ForkedProcessRunner.class.getName());
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

        List<String> trace = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                callback.forkedEmbeddedMessage(line);
                if(trace != null) {
                    trace.add(line);
                } else if(FORKED_EMBEDDED_ERROR_START.equals(line)) {
                    trace = new ArrayList<>();
                }
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
            if (exitCode != 0) {
                Throwable t = null;
                if(trace != null) {
                    t = parseException(trace, 0);
                    if(t == null) {
                        System.out.println(FORKED_EMBEDDED_ERROR_START);
                        for(String l : trace) {
                            System.out.println(l);
                        }
                    }
                }
                throw new ProvisioningException("Forked embedded process has failed", t);
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





    private static void collectCpUrls(String javaHome, ClassLoader cl, StringBuilder buf) throws ProvisioningException {
        final ClassLoader parentCl = cl.getParent();
        if(parentCl != null) {
            collectCpUrls(javaHome, cl.getParent(), buf);
        }
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader)cl).getURLs()) {
                final String file;
                try {
                    file = new File(url.toURI()).getAbsolutePath();
                } catch (URISyntaxException ex) {
                    throw new ProvisioningException(ex);
                }
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

    private static Throwable parseException(List<String> trace, int offset) {
        final String classAndMsg = trace.get(offset);
        String className = null;
        List<Class<?>> ctorArgTypes = Collections.emptyList();
        List<Object> ctorArgs = Collections.emptyList();
        int i = classAndMsg.indexOf(':');
        if(i < 0) {
            className = classAndMsg;
        } else {
            className = classAndMsg.substring(0, i);
            ctorArgTypes = CollectionUtils.add(ctorArgTypes, String.class);
            ctorArgs = CollectionUtils.add(ctorArgs, classAndMsg.substring(i + 1).trim());
        }

        final List<StackTraceElement> stack = new ArrayList<>(trace.size() - offset - 1);
        for(i = offset + 1; i < trace.size(); ++i) {
            final StackTraceElement ste = stackTraceElementFromString(trace.get(i));
            if(ste == null) {
                final Throwable cause = parseException(trace, i);
                if(cause == null) {
                    return null;
                }
                ctorArgTypes = CollectionUtils.add(ctorArgTypes, Throwable.class);
                ctorArgs = CollectionUtils.add(ctorArgs, cause);
                break;
            }
            stack.add(ste);
        }

        final Throwable t;
        try {
            final Class<?> excClass = Thread.currentThread().getContextClassLoader().loadClass(className);
            if(ctorArgTypes.isEmpty()) {
                t = (Throwable) excClass.newInstance();
            } else {
                t = (Throwable) excClass.getConstructor(ctorArgTypes.toArray(new Class[ctorArgTypes.size()]))
                        .newInstance(ctorArgs.toArray(new Object[ctorArgs.size()]));
            }
        } catch (Throwable e) {
            return null;
        }
        t.setStackTrace(stack.toArray(new StackTraceElement[stack.size()]));
        return t;
    }

    private static StackTraceElement stackTraceElementFromString(String line) {
        int i = line.length() - 1;
        if(i < 0) {
            return null;
        }
        if(line.charAt(i) != ')') {
            return null;
        }
        int pos = line.lastIndexOf(':');
        if(pos < 0) {
            return null;
        }
        final int lineNumber = Integer.parseInt(line.substring(pos + 1, i));
        i = pos;
        pos = line.lastIndexOf('(');
        if(pos < 0) {
            return null;
        }
        final String file = line.substring(pos + 1, i);
        i = pos;
        while(--pos >= 0) {
            if(line.charAt(pos) == '.') {
                break;
            }
        }
        if(pos < 0) {
            return null;
        }
        final String method = line.substring(pos + 1, i);
        i = pos;
        while(--pos >= 0) {
            if(Character.isWhitespace(line.charAt(pos))) {
                break;
            }
        }
        if(pos < 0) {
            return null;
        }
        return new StackTraceElement(line.substring(pos + 1, i), method, file, lineNumber);
    }
}
