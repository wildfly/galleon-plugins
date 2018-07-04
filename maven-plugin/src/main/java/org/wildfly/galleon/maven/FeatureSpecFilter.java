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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.maven.plugin.logging.Log;
import org.jboss.galleon.spec.CapabilitySpec;
import org.jboss.galleon.spec.FeatureAnnotation;
import org.jboss.galleon.spec.FeatureDependencySpec;
import org.jboss.galleon.spec.FeatureReferenceSpec;
import org.jboss.galleon.spec.FeatureSpec;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class FeatureSpecFilter {

    public static boolean areIdentical(FeatureSpec standalone, FeatureSpec domain, Log log) {
        final String domainName = domain.getName();
        final String standaloneName = standalone.getName();
        if (domainName.equals("profile." + standaloneName)) {
            return sameCapabilities(standalone.getProvidedCapabilities(), domain.getProvidedCapabilities(), log)
                    && sameCapabilities(standalone.getRequiredCapabilities(), domain.getRequiredCapabilities(), log)
                    && samePackages(standalone, domain, log)
                    && sameAnnotations(standalone, domain)
                    && sameProfileFeatureReferences(standalone, domain);
        } else if (domainName.equals("host." + standaloneName)) { //host subsystem
            return sameCapabilities(standalone.getProvidedCapabilities(), domain.getProvidedCapabilities(), log)
                    && sameCapabilities(standalone.getRequiredCapabilities(), domain.getRequiredCapabilities(), log)
                    && samePackages(standalone, domain, log)
                    && sameAnnotations(standalone, domain)
                    && sameHostFeatureReferences(standalone, domain);
        } else if(domainName.equals("domain." + standaloneName)) {
            return sameCapabilities(standalone.getProvidedCapabilities(), domain.getProvidedCapabilities(), log)
                    && sameCapabilities(standalone.getRequiredCapabilities(), domain.getRequiredCapabilities(), log)
                    && samePackages(standalone, domain, log)
                    && sameAnnotations(standalone, domain);
        }
        return false;
    }

    private static boolean sameCapabilities(Set<CapabilitySpec> standaloneCaps, Set<CapabilitySpec> domainCaps, Log log) {
        final Set<String> domain = domainCaps.stream().map(CapabilitySpec::toString).map(s -> s.startsWith("$profile.") ? s.substring(9) : s).collect(Collectors.toSet());
        final Set<String> capabilities = standaloneCaps.stream().map(CapabilitySpec::toString).filter(s -> !domain.contains(s)).collect(Collectors.toSet());
        capabilities.forEach(s -> log.warn("We haven't found the capability " + s + " in domain spec"));
        return capabilities.isEmpty();
    }

    private static boolean samePackages(FeatureSpec standalone, FeatureSpec domain, Log log) {
        if (standalone.hasFeatureDeps() && domain.hasFeatureDeps()) {
            Set<FeatureDependencySpec> packages = standalone.getFeatureDeps().stream().filter(s -> !domain.getFeatureDeps().contains(s)).collect(Collectors.toSet());
            packages.forEach(s -> log.warn("We haven't found the package " + s + " in domain spec"));
            return packages.isEmpty();
        }
        return standalone.hasFeatureDeps() == domain.hasFeatureDeps();
    }

    private static boolean sameAnnotations(FeatureSpec standalone, FeatureSpec domain) {
        if (standalone.hasAnnotations() && domain.hasAnnotations()) {
            final Map<String, FeatureAnnotation> domainAnnotations = domain.getAnnotations().stream().collect(Collectors.toMap(FeatureAnnotation::getName, Function.identity()));
            Set<FeatureAnnotation> annotations = standalone.getAnnotations().stream().filter(s -> !domainAnnotations.containsKey(s.getName()) && !sameAnnotation(s, domainAnnotations.get(s.getName()))).collect(Collectors.toSet());
            return annotations.isEmpty();
        }
        return false;
    }

    private static boolean sameAnnotation(FeatureAnnotation standaloneAnnotation, FeatureAnnotation domainAnnotation) {
        if (standaloneAnnotation.getElements().size() == domainAnnotation.getElements().size()) {
            for (String name : standaloneAnnotation.getElements().keySet()) {
                List<String> standaloneValues = standaloneAnnotation.getElementAsList(name);
                List<String> domainValues = domainAnnotation.getElementAsList(name);
                if (standaloneValues.size() == domainValues.size()) {
                    List<String> values = standaloneValues.stream().filter(s -> !domainValues.contains(s)).collect(Collectors.toList());
                    return values.isEmpty();
                }
            }
        }
        return false;
    }

    private static boolean sameProfileFeatureReferences(FeatureSpec standalone, FeatureSpec domain) {
        final Set<String> domainRefs = domain.getFeatureRefs().stream().map(ref -> ref.getName().startsWith("profile.") ? ref.getName().substring("profile.".length()) : ref.getName()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        Set<String> refs = standalone.getFeatureRefs().stream().map(FeatureReferenceSpec::getName).filter(s -> !domainRefs.contains(s)).collect(Collectors.toSet());
        return refs.isEmpty();
    }

    private static boolean sameHostFeatureReferences(FeatureSpec standalone, FeatureSpec domain) {
        final Set<String> domainRefs = domain.getFeatureRefs().stream().map(ref -> ref.getName().startsWith("host.") ? ref.getName().substring("host.".length()) : ref.getName()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        Set<String> refs = standalone.getFeatureRefs().stream().map(FeatureReferenceSpec::getName).filter(s -> !domainRefs.contains(s)).collect(Collectors.toSet());
        return refs.isEmpty();
    }
}
