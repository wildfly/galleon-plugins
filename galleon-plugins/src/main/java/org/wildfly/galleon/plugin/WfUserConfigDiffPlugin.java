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
import java.util.List;

import org.jboss.galleon.Errors;
import org.jboss.galleon.MessageWriter;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.layout.FeaturePackLayout;
import org.jboss.galleon.layout.ProvisioningLayout;
import org.jboss.galleon.model.Gaec;
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

        final VersionResolver versionResolver = getPropertyResolver(layout);

        final URL[] cp = new URL[4];
        try {
            cp[0] = configGenJar.toUri().toURL();
            cp[1] = resolve(currentState, "jboss-modules.jar").toUri().toURL();
            final RepositoryArtifactResolver maven = layout.getFactory().getUniverseResolver().getArtifactResolver("repository.maven");
            cp[2] = maven.resolve(versionResolver.resolveVersion(Gaec.parse("org.wildfly.core:wildfly-cli:jar:client"))).getPath().toUri().toURL();
            cp[3] = maven.resolve(versionResolver.resolveVersion(Gaec.parse("org.wildfly.core:wildfly-launcher:jar:"))).getPath().toUri().toURL();
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

    private VersionResolver getPropertyResolver(ProvisioningLayout<?> layout) throws ProvisioningException {
        final VersionResolver.Builder vrBuilder = VersionResolver.builder();
        for(FeaturePackLayout fp : layout.getOrderedFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }

            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            try {
                vrBuilder.load(artifactProps);
            } catch (IOException e) {
                throw new ProvisioningException("Could not load "+ artifactProps, e);
            }
        }
        return vrBuilder.build();
    }

}
