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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jboss.galleon.Errors;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.runtime.PackageRuntime;
import org.jboss.galleon.util.CollectionUtils;
import org.wildfly.galleon.plugin.WfConstants;
import org.wildfly.galleon.plugin.WfInstallPlugin;
import org.wildfly.galleon.plugin.WildFlyPackageTask;

/**
 *
 * @author Alexey Loubyansky
 */
public class XmlMerge implements WildFlyPackageTask {

    private String basedir;
    private List<FileFilter> filters = Collections.emptyList();
    private String output;

    public XmlMerge() {
    }

    public void setBasedir(String basedir) {
        this.basedir = basedir;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void addFilter(FileFilter filter) {
        filters = CollectionUtils.add(filters, filter);
    }

    public boolean includeFile(final String path) {
        for(FileFilter filter : filters) {
            if(filter.matches(path)) {
                return filter.isInclude();
            }
        }
        return false; //default include
    }

    @Override
    public Phase getPhase() {
        return Phase.FINALIZING;
    }

    @Override
    public void execute(WfInstallPlugin plugin, PackageRuntime pkg) throws ProvisioningException {

        Path srcDir = plugin.getRuntime().getStagedDir();
        if(basedir != null) {
            srcDir = srcDir.resolve(basedir);
        }
        if(!Files.exists(srcDir)) {
            plugin.getRuntime().getMessageWriter().print("WARN: base dir %s for xml-merge does not exist", srcDir);
            return;
        }

        // collect the files to merge into a comma-separated list
        final StringBuilder buf = new StringBuilder();
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(srcDir)) {
            for(Path p : stream) {
                if(includeFile(p.toString())) {
                    if(buf.length() > 0) {
                        buf.append(',');
                    }
                    buf.append(p.toString());
                }
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readDirectory(srcDir));
        }
        if(buf.length() == 0) {
            return;
        }

        final Path pmWf = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
        final Path mergerXsl = pmWf.resolve("merger.xsl");
        if(!Files.exists(mergerXsl)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(mergerXsl));
        }

        final Path mergedXml = plugin.getRuntime().getStagedDir().resolve(output);

        try(OutputStream out = Files.newOutputStream(mergedXml)) {
            final Transformer transformer = plugin.getXslTransformer(mergerXsl);
            transformer.setParameter("fileList", buf.toString());
            transformer.setParameter("fileSeparator", File.separator);

            final DOMSource source = new DOMSource(plugin.getXmlDocumentBuilderFactory().newDocumentBuilder().newDocument());

            final StreamResult result = new StreamResult(out);
            transformer.transform(source, result);
        } catch (Exception e) {
            throw new ProvisioningException("Failed to transform", e);
        }
    }
}
