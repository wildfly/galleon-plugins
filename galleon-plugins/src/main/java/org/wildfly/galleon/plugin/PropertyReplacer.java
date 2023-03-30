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


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility that copies content from reader to writer replacing the properties.
 *
 * @author Alexey Loubyansky
 */
public class PropertyReplacer {

    private static final int INITIAL = 0;
    private static final int GOT_DOLLAR = 1;
    private static final int GOT_OPEN_BRACE = 2;
    private static final int RESOLVED = 3;
    private static final int DEFAULT = 4;

    public static void copy(final Path src, final Path target, PropertyResolver resolver, String failureReplacement) throws IOException {
        if(!Files.exists(target.getParent())) {
            Files.createDirectories(target.getParent());
        }
        try(BufferedReader reader = Files.newBufferedReader(src);
                BufferedWriter writer = Files.newBufferedWriter(target)) {
            copy(reader, writer, resolver, failureReplacement);
        }
    }

    public static void copy(final Reader reader, Writer writer, PropertyResolver properties,
            String failureReplacement) throws IOException {
        int state = INITIAL;
        final StringBuilder buf = new StringBuilder();
        int ch = reader.read();
        while (ch >= 0) {
            switch (state) {
                case INITIAL: {
                    switch (ch) {
                        case '$': {
                            state = GOT_DOLLAR;
                            break;
                        }
                        default: {
                            writer.write(ch);
                        }
                    }
                    break;
                }
                case GOT_DOLLAR: {
                    switch (ch) {
                        case '$': {
                            // escaped $
                            buf.setLength(0);
                            writer.write(ch);
                            state = INITIAL;
                            break;
                        }
                        case '{': {
                            state = GOT_OPEN_BRACE;
                            break;
                        }
                        default: {
                            // invalid; emit and resume
                            writer.append('$');
                            writer.write(ch);
                            buf.setLength(0);
                            state = INITIAL;
                        }
                    }
                    break;
                }
                case GOT_OPEN_BRACE: {
                    switch (ch) {
                        case '}':
                        case ',': {
                            final String name = buf.toString();
                            if ("/".equals(name)) {
                                writer.append(File.separatorChar);
                                state = ch == '}' ? INITIAL : RESOLVED;
                            } else {
                                final String val = properties.resolveProperty(name);
                                if (val != null) {
                                    writer.write(val);
                                    state = ch == '}' ? INITIAL : RESOLVED;
                                } else if (ch == ',') {
                                    state = DEFAULT;
                                } else {
                                    if(failureReplacement != null) {
                                        writer.write(failureReplacement);
                                        state = ch == '}' ? INITIAL : RESOLVED;
                                    } else {
                                        throw new IllegalStateException("Failed to resolve property: " + buf);
                                    }
                                }
                            }
                            buf.setLength(0);
                            break;
                        }
                        default: {
                            buf.appendCodePoint(ch);
                        }
                    }
                    break;
                }
                case RESOLVED: {
                    if (ch == '}') {
                        state = INITIAL;
                    }
                    break;
                }
                case DEFAULT: {
                    if (ch == '}') {
                        state = INITIAL;
                        final String val = properties.resolveProperty(buf.toString());
                        if (val != null) {
                            writer.write(val);
                        } else {
                            writer.write(buf.toString());
                        }
                    } else {
                        buf.appendCodePoint(ch);
                    }
                    break;
                }
                default:
                    throw new IllegalStateException("Unexpected char seen: " + ch);
            }
            ch = reader.read();
        }
        switch (state) {
            case GOT_DOLLAR: {
                writer.append('$');
                break;
            }
            case DEFAULT: {
                writer.write(buf.toString());
                break;
            }
            case GOT_OPEN_BRACE: {
                    throw new IllegalStateException("Incomplete expression: " + buf.toString());
            }
        }
    }
}