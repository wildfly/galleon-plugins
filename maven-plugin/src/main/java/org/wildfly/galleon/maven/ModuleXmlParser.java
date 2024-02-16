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
package org.wildfly.galleon.maven;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Thomas.Diesler@jboss.com
 * @author Stuart Douglas
 * @author Eduardo Martins
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @since 06-Sep-2012
 */
class ModuleXmlParser {
    /**
     * pattern for artifacts in the JBoss Modules format
     */
    private static final Pattern JBOSS_MODULES_VALID_PATTERN = Pattern.compile("^([-_a-zA-Z0-9.]+):([-_a-zA-Z0-9.]+):([-_a-zA-Z0-9.]+)(?::([-_a-zA-Z0-9.]+))?$");

    static ModuleParseResult parse(final Path file, String encoding, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) throws IOException, ParsingException {
        try(Reader is = Files.newBufferedReader(file, Charset.forName(encoding))) {
            return parse(is, targetToAlias);
        }
    }

    static void populateAlias(final Path file, String encoding, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) throws IOException, ParsingException {
        try(Reader is = Files.newBufferedReader(file, Charset.forName(encoding))) {
            populateAlias(is, targetToAlias);
        }
    }

    private static void populateAlias(final Reader r, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) throws IOException, ParsingException {
        Builder builder = new Builder(false);
        final Document document = builder.build(r);
        ModuleParseResult result = new ModuleParseResult(document);
        final Element rootElement = document.getRootElement();
        if (rootElement.getLocalName().equals("module-alias")) {
            populateModuleAlias(rootElement, targetToAlias);
        }
    }

    private static void populateModuleAlias(Element element, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) {
        final String targetName = getOptionalAttributeValue(element, "target-name", "");
        final String targetSlot = getOptionalAttributeValue(element, "target-slot", "main");
        final String name = element.getAttributeValue("name");
        ModuleIdentifier targetModuleId = getModuleIdentifier(targetName, targetSlot);
        Set<ModuleIdentifier> aliases = targetToAlias.get(targetModuleId);
        if (aliases == null) {
            aliases = new HashSet<>();
            targetToAlias.put(targetModuleId, aliases);
        }
        final String slot = getOptionalAttributeValue(element, "slot", "main");
        ModuleIdentifier aliasModuleId = getModuleIdentifier(name, slot);
        aliases.add(aliasModuleId);
    }

    private static ModuleIdentifier getModuleIdentifier(String name, String slot) {
        ModuleIdentifier moduleId;
        if (name.indexOf(':') < 0) {
            moduleId = new ModuleIdentifier(name, slot);
        } else {
            moduleId = ModuleIdentifier.fromString(name);
        }
        return moduleId;
    }

    private static ModuleParseResult parse(final Reader r, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) throws IOException, ParsingException {
        Builder builder = new Builder(false);
        final Document document = builder.build(r);
        ModuleParseResult result = new ModuleParseResult(document);
        final Element rootElement = document.getRootElement();
        if (rootElement.getLocalName().equals("module-alias")) {
            parseModuleAlias(rootElement, result);
        } else if (rootElement.getLocalName().equals("module")) {
            parseModule(rootElement, result, targetToAlias);
        }
        return result;
    }

    private static void parseModule(Element element, ModuleParseResult result, Map<ModuleIdentifier, Set<ModuleIdentifier>> targetToAlias) throws ParsingException {
        String name = element.getAttributeValue("name");
        String slot = getOptionalAttributeValue(element, "slot", "main");
        result.identifier = getModuleIdentifier(name, slot);
        final Attribute versionAttribute = element.getAttribute("version");
        if (versionAttribute != null) {
            result.versionArtifactName = parseOptionalArtifactName(versionAttribute.getValue(), versionAttribute);
        }
        final Element props = element.getFirstChildElement("properties", element.getNamespaceURI());
        if (props != null) {
            Map<String, String> properties = parseProperties(props);
            result.getProperties().putAll(properties);
        }
        final Element dependencies = element.getFirstChildElement("dependencies", element.getNamespaceURI());
        if (dependencies != null) parseDependencies(dependencies, result);
        final Element resources = element.getFirstChildElement("resources", element.getNamespaceURI());
        if (resources != null) parseResources(resources, result);
        Set<ModuleIdentifier> aliases = targetToAlias.get(result.identifier);
        if (aliases != null) {
            for (ModuleIdentifier alias : aliases) {
                result.dependencies.add(new ModuleParseResult.ModuleDependency(alias, false, Collections.emptyMap()));
            }
        }
    }

    private static String getOptionalAttributeValue(Element element, String name, String defVal) {
        final String value = element.getAttributeValue(name);
        return value == null ? defVal : value;
    }

    private static void parseModuleAlias(Element element, ModuleParseResult result) {
        final String targetName = getOptionalAttributeValue(element, "target-name", "");
        final String targetSlot = getOptionalAttributeValue(element, "target-slot", "main");
        final String name = element.getAttributeValue("name");
        final String slot = getOptionalAttributeValue(element, "slot", "main");
        ModuleIdentifier moduleId = getModuleIdentifier(targetName, targetSlot);
        result.identifier = getModuleIdentifier(name, slot);
        result.dependencies.add(new ModuleParseResult.ModuleDependency(moduleId, false, Collections.emptyMap()));
    }

    private static void parseDependencies(Element element, ModuleParseResult result) throws ParsingException {
        final Elements modules = element.getChildElements("module", element.getNamespaceURI());
        final int size = modules.size();
        for (int i = 0; i < size; i ++) {
            final Element moduleElement = modules.get(i);
            final String name = getOptionalAttributeValue(moduleElement, "name", "");
            final String slot = getOptionalAttributeValue(moduleElement, "slot", "main");
            final ModuleIdentifier moduleId = getModuleIdentifier(name, slot);
            final boolean optional = Boolean.parseBoolean(getOptionalAttributeValue(moduleElement, "optional", "false"));

            final Element properties = moduleElement.getFirstChildElement("properties", moduleElement.getNamespaceURI());
            final Map<String, String> props = properties != null ? parseProperties(properties) : Collections.emptyMap();

            result.dependencies.add(new ModuleParseResult.ModuleDependency(moduleId, optional, props));
        }
    }

    private static Map<String, String> parseProperties(Element element) throws ParsingException {
        final Elements properties = element.getChildElements("property", element.getNamespaceURI());
        final int size = properties.size();
        if(size == 0) {
            return Collections.emptyMap();
        }
        final Map<String, String> props = new HashMap<>(size);
        for (int i = 0; i < size; i ++) {
            final Element propertyElement = properties.get(i);
            final String name = propertyElement.getAttributeValue("name");
            if(name == null) {
                throw new ParsingException("Element property is missing required attribute name");
            }
            final String value = propertyElement.getAttributeValue("value");
            if(value == null) {
                throw new ParsingException("Element property is missing required attribute value");
            }
            props.put(name, value);
        }
        return Collections.unmodifiableMap(props);
    }

    private static void parseResources(Element element, ModuleParseResult result) {
        final Elements children = element.getChildElements();
        final int size = children.size();
        for (int i = 0; i < size; i ++) {
            final Element child = children.get(i);
            switch (child.getLocalName()) {
                case "resource-root": {
                    String path = child.getAttributeValue("path");
                    if (path != null) result.resourceRoots.add(path);
                    break;
                }
                case "artifact": {
                    final Attribute attribute = child.getAttribute("name");
                    if (attribute != null) {
                        final String nameStr = attribute.getValue();
                        result.artifacts.add(parseArtifactName(nameStr, attribute));
                        break;
                    }
                }
            }
        }
    }

    private static ModuleParseResult.ArtifactName parseArtifactName(String artifactName, final Attribute attribute) {
        final ModuleParseResult.ArtifactName name = parseOptionalArtifactName(artifactName, attribute);
        if (name == null) {
            //this happens if the artifact is not enclosed in a ${} match
            //we still support this, as long as a hard coded version is present
            //we initially try and match the jboss-modules fromat, of G:A:V[:Q]
            //if this does not match we use the standard format used elsewhere of G:A:[:P[:Q[:V]]]
            final Matcher matcher = JBOSS_MODULES_VALID_PATTERN.matcher(artifactName);
            if (matcher.matches()) {
                StringBuilder sb = new StringBuilder();
                sb.append(matcher.group(1)) //group
                    .append(":")
                    .append(matcher.group(2)) //artifact
                    .append("::"); //packaging is always null (jar)
                if (matcher.group(4) != null) {
                    //there is a classifier present
                    sb.append(matcher.group(4));
                }
                sb.append(":").append(matcher.group(3));
                return new ModuleParseResult.ArtifactName(sb.toString(), null, attribute);
            }
            throw new RuntimeException("Hard coded artifact " + artifactName);
        }
        return name;
    }

    private static ModuleParseResult.ArtifactName parseOptionalArtifactName(String artifactName, final Attribute attribute) {
        if (artifactName.startsWith("${") && artifactName.endsWith("}")) {
            String ct = artifactName.substring(2, artifactName.length() - 1);
            String options = null;
            String artifactCoords = ct;
            if (ct.contains("?")) {
                String[] split = ct.split("\\?");
                options = split[1];
                artifactCoords = split[0];
            }
            return new ModuleParseResult.ArtifactName(artifactCoords, options, attribute);
        } else {
            return null;
        }
    }
}
