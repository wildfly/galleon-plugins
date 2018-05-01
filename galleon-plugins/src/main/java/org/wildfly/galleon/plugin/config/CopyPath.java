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
package org.wildfly.galleon.plugin.config;

import javax.xml.stream.XMLStreamException;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 *
 * @author Alexey Loubyansky
 */
public class CopyPath implements WildFlyPackageTask {

    private static final String RELATIVE_TO_CONTENT = "content";
    private static final String RELATIVE_TO_RESOURCES = "resources";

    private boolean relativeToContent;
    private String src;
    private String target;
    private boolean replaceProperties;

    public CopyPath() {
    }

    public void setRelativeTo(String relativeTo) throws XMLStreamException {
        if(relativeTo.equals(RELATIVE_TO_CONTENT)) {
            relativeToContent = true;
        } else if(!relativeTo.equals(RELATIVE_TO_RESOURCES)) {
            throw new XMLStreamException("Unexpected relative-to value " + relativeTo);
        }
    }

    public void setSrc(String src) {
        this.src = src;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public void setReplaceProperties(boolean replaceProperties) {
        this.replaceProperties = replaceProperties;
    }

    public String getSrc() {
        return src;
    }

    public String getTarget() {
        return target;
    }

    public boolean isReplaceProperties() {
        return replaceProperties;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {
        plugin.copyPath(relativeToContent ? pkg.getContentDir() : pkg.getResource(WfConstants.PM, WfConstants.WILDFLY), this);
    }
}
