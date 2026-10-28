/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.maven.build.tasks;

import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.wildfly.galleon.maven.AbstractFeaturePackBuildMojo;

/**
 *
 * @author Alexey Loubyansky
 */
public interface ResourcesTask {

    void execute(AbstractFeaturePackBuildMojo builder, Path resourcesDir) throws MojoExecutionException;
}
