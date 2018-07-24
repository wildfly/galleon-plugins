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

package org.wildfly.galleon.plugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.plugin.UserConfigDiffPlugin;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.state.ProvisionedState;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfUserConfigDiffPlugin implements UserConfigDiffPlugin {

    private static final String WF_DIFF_CONFIG_GENERATOR = "org.wildfly.galleon.plugin.config.generator.WfConfigsReader";

    @Override
    public void userConfigDiff(ProvisionedState provisionedState, ProvisioningLayout<?> layout,
            Path currentState, MessageWriter log) throws ProvisioningException {

        log.verbose("WildFly User Config Diff Plugin");

        final Path configGenJar = layout.getResource("wildfly/wildfly-config-gen.jar");
        if(!Files.exists(configGenJar)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(configGenJar));
        }

        final PropertyResolver propertyResolver = getPropertyResolver(layout);

        final URL[] cp = new URL[4];
        try {
            cp[0] = configGenJar.toUri().toURL();
            cp[1] = resolve(currentState, "jboss-modules.jar").toUri().toURL();
            final RepositoryArtifactResolver maven = layout.getFactory().getUniverseResolver().getArtifactResolver("repository.maven");
            cp[2] = maven.resolve(toArtifactCoords("org.wildfly.core:wildfly-cli::client", propertyResolver)).toUri().toURL();
            cp[3] = maven.resolve(toArtifactCoords("org.wildfly.core:wildfly-launcher", propertyResolver)).toUri().toURL();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to init classpath", e);
        }
        if(log.isVerboseEnabled()) {
            log.verbose("Config diff generator classpath:");
            for(int i = 0; i < cp.length; ++i) {
                log.verbose(i+1 + ". " + cp[i]);
            }
        }

        List<ConfigModel> configs;
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        final URLClassLoader configGenCl = new URLClassLoader(cp, originalCl);
        Thread.currentThread().setContextClassLoader(configGenCl);
        try {
            final Class<?> wfDiffGenerator = configGenCl.loadClass(WF_DIFF_CONFIG_GENERATOR);
            final Method exportDiff = wfDiffGenerator.getMethod("exportDiff", ProvisioningLayout.class, ProvisionedState.class, MessageWriter.class, Path.class);
            configs = (List<ConfigModel>) exportDiff.invoke(null, layout, provisionedState, log, currentState);
        } catch(InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if(cause instanceof ProvisioningException) {
                throw (ProvisioningException)cause;
            } else {
                throw new ProvisioningException("Failed to invoke config diff generator " + WF_DIFF_CONFIG_GENERATOR, cause);
            }
        } catch (Throwable e) {
            throw new ProvisioningException("Failed to initialize config diff generator " + WF_DIFF_CONFIG_GENERATOR, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            try {
                configGenCl.close();
            } catch (IOException e) {
            }
        }
    }

    private Path resolve(Path currentState, String... path) throws ProvisioningException {
        Path p = currentState;
        for(String s : path) {
            p = p.resolve(s);
        }
        if(!Files.exists(p)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(p));
        }
        return p;
    }

    private PropertyResolver getPropertyResolver(ProvisioningLayout<?> layout) throws ProvisioningException {
        final Map<String, String> artifactVersions = new HashMap<>();
        for(FeaturePackLayout fp : layout.getOrderedFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }

            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            if(Files.exists(artifactProps)) {
                try (Stream<String> lines = Files.lines(artifactProps)) {
                    final Iterator<String> iterator = lines.iterator();
                    while (iterator.hasNext()) {
                        final String line = iterator.next();
                        final int i = line.indexOf('=');
                        if (i < 0) {
                            throw new ProvisioningException("Failed to locate '=' character in " + line);
                        }
                        artifactVersions.put(line.substring(0, i), line.substring(i + 1));
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(artifactProps), e);
                }
            }
        }
        return new MapPropertyResolver(artifactVersions);
    }

    private String toArtifactCoords(String str, PropertyResolver versionResolver) throws ProvisioningException {

        String[] parts = str.split(":");
        if(parts.length < 2) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = null;
        String classifier = "";
        String ext = "jar";
        if(parts.length > 2) {
            if(!parts[2].isEmpty()) {
                version = parts[2];
            }
            if(parts.length > 3) {
                classifier = parts[3];
                if(parts.length > 4 && !parts[4].isEmpty()) {
                    ext = parts[4];
                    if (parts.length > 5) {
                        throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
                    }
                }
            }
        }

        if(version != null) {
            return groupId + ':' + artifactId + ':' + ext + ':' + classifier + ':' + version;
        }

        final String resolvedStr = versionResolver.resolveProperty(groupId + ':' + artifactId);
        if (resolvedStr == null) {
            throw new ProvisioningException("Failed to resolve the version of " + str);
        }

        parts = resolvedStr.split(":");
        if(parts.length < 3) {
            throw new ProvisioningException("Failed to resolve version for artifact: " + resolvedStr);
        }
        return groupId + ':' + artifactId + ':' + ext + ':' + classifier + ':' + parts[2];
    }
}
