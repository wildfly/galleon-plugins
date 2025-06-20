/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.galleon.plugin.doc.generator;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkus.qute.Engine;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.Variant;

public class TemplateUtils {

    static Engine engine() {
        return Engine.builder()
                .addLocator(new TemplateLocator() {
                    @Override
                    public Optional<TemplateLocation> locate(String id) {
                        String resourcePath = "/templates/" + id + ".html";
                        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
                            if (stream != null) {
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
                                    String content = reader.lines().collect(Collectors.joining("\n"));

                                    return Optional.of(new TemplateLocation() {
                                        @Override
                                        public Reader read() {
                                            return new StringReader(content);
                                        }

                                        @Override
                                        public Optional<Variant> getVariant() {
                                            return Optional.of(new Variant(Locale.getDefault(), UTF_8, Variant.TEXT_HTML));
                                        }
                                    });
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return Optional.empty();
                    }
                })
                .addDefaults()
                .addValueResolver(new ReflectionValueResolver())
                .build();
    }
}
