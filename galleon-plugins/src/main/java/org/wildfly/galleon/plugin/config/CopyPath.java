/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
