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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.diff.FileSystemMerge;
import org.jboss.galleon.diff.ProvisioningDiffResult;
import org.jboss.galleon.diff.Strategy;
import org.jboss.galleon.plugin.UpgradePlugin;
import org.jboss.galleon.runtime.ProvisioningRuntime;
import org.wildfly.galleon.plugin.server.EmbeddedServerInvoker;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class WfUpgradePlugin implements UpgradePlugin {

    @Override
    public void upgrade(ProvisioningRuntime runtime, ProvisioningDiffResult diff, Path customizedInstallation) throws ProvisioningException {
        try {
            //FileSystemMerge fsMerge = FileSystemMerge.Factory.getInstance(Strategy.OURS, runtime.getMessageWriter(),runtime.getInstallDir(), customizedInstallation);
            FileSystemMerge fsMerge = FileSystemMerge.Factory.getInstance(Strategy.OURS, runtime.getMessageWriter(),runtime.getStagedDir(), customizedInstallation);
            fsMerge.executeUpdate(diff);
            //EmbeddedServerInvoker embeddedServer = new EmbeddedServerInvoker(runtime.getMessageWriter(), runtime.getInstallDir().toAbsolutePath(), null);
            EmbeddedServerInvoker embeddedServer = new EmbeddedServerInvoker(runtime.getMessageWriter(), runtime.getStagedDir().toAbsolutePath(), null);
            for(Path script :  ((WfDiffResult)diff).getScripts()) {
                List<String> lines = Files.readAllLines(script);
                embeddedServer.execute(lines);
            }
        } catch (IOException ex) {
            runtime.getMessageWriter().error(ex, "Error upgrading");
            throw new ProvisioningException(ex.getMessage(), ex);
        }
    }
}
