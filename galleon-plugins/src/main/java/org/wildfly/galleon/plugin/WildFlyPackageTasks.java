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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.xml.stream.XMLStreamException;

import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.config.LineEndingsTask;
import org.wildfly.galleon.plugin.config.WildFlyPackageTasksParser;


/**
 *
 * @author Alexey Loubyansky
 */
public class WildFlyPackageTasks {

    public static class Builder {

        private List<String> mkDirs = Collections.emptyList();
        private List<LineEndingsTask> lineEndings = Collections.emptyList();

        private List<WildFlyPackageTask> tasks = Collections.emptyList();

        private Builder() {
        }

        public Builder addTask(WildFlyPackageTask task) {
            tasks = CollectionUtils.add(tasks, task);
            return this;
        }

        public Builder addMkDir(String mkdirs) {
            mkDirs = CollectionUtils.add(mkDirs, mkdirs);
            return this;
        }

        public Builder addLineEndings(LineEndingsTask lineEndingsTask) {
            lineEndings = CollectionUtils.add(lineEndings, lineEndingsTask);
            return this;
        }

        public WildFlyPackageTasks build() {
            return new WildFlyPackageTasks(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WildFlyPackageTasks load(Path configFile) throws ProvisioningException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            return new WildFlyPackageTasksParser().parse(configStream);
        } catch (XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.openFile(configFile), e);
        }
    }

    private final List<String> mkDirs;
    private final List<LineEndingsTask> lineEndings;
    private final List<WildFlyPackageTask> tasks;

    private WildFlyPackageTasks(Builder builder) {
        this.mkDirs = CollectionUtils.unmodifiable(builder.mkDirs);
        this.lineEndings = CollectionUtils.unmodifiable(builder.lineEndings);
        this.tasks = CollectionUtils.unmodifiable(builder.tasks);
    }

    public boolean hasMkDirs() {
        return !mkDirs.isEmpty();
    }

    public List<String> getMkDirs() {
        return mkDirs;
    }

    public List<LineEndingsTask> getLineEndings() {
        return lineEndings;
    }

    public boolean hasTasks() {
        return !tasks.isEmpty();
    }

    public List<WildFlyPackageTask> getTasks() {
        return tasks;
    }
}
