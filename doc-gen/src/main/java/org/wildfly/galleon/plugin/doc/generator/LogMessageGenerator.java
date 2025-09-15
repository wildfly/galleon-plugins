/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.galleon.plugin.doc.generator;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.EnumMemberValue;
import javassist.bytecode.annotation.IntegerMemberValue;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.StringMemberValue;

// copied from https://github.com/wildfly/wildscribe/blob/main/message-dumper/src/main/java/org/wildscribe/logs/MessageExporter.java
/**
 * A utility to export message id's and messages from Jar files.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class LogMessageGenerator {

    // Annotation type names
    private static final String MESSAGE_LOGGER_ANNOTATION = "org.jboss.logging.annotations.MessageLogger";
    private static final String LOG_MESSAGE_ANNOTATION = "org.jboss.logging.annotations.LogMessage";
    private static final String MESSAGE_ANNOTATION = "org.jboss.logging.annotations.Message";

    // Default values
    private static final int DEFAULT_MESSAGE_ID_LENGTH = 6;
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final String VOID_DESCRIPTOR = "V";
    private static final String VOID_TYPE = "void";

    // File extensions
    private static final String JAR_EXTENSION = ".jar";
    private static final String CLASS_EXTENSION = ".class";

    /**
     * Exports log messages from JAR files listed in the artifact list file.
     *
     * @param log the logger for debug output
     * @param artifactListFile CSV file containing SHA-256 checksums and file paths
     * @param localRepositoryPath the base path of the local Maven repository
     * @return list of extracted log messages
     * @throws IOException if there's an error reading the artifact list file
     */
    static List<LogMessage> exportLogMessages(SimpleLog log, Path artifactListFile, Path localRepositoryPath) throws IOException {
        List<LogMessage> messages = new ArrayList<>();

        List<String> lines = Files.readAllLines(artifactListFile);
        for (String line : lines) {
            processArtifactLine(log, line, localRepositoryPath, messages);
        }

        return messages;
    }

    /**
     * Processes a single line from the artifact list CSV file.
     *
     * @param log the logger for debug output
     * @param line the CSV line to process
     * @param localRepositoryPath the base path of the local Maven repository
     * @param messages collection to add extracted log messages to
     */
    private static void processArtifactLine(SimpleLog log, String line, Path localRepositoryPath, List<LogMessage> messages) {
        if (line.trim().isEmpty()) {
            return;
        }

        // Parse CSV line: sha256sum,filepath
        String[] parts = line.split(",", 2);
        if (parts.length != 2) {
            log.debug("Invalid CSV line format: " + line);
            return;
        }

        Path filePath = Path.of(parts[1].trim());
        if (isJarFile(filePath)) {
            Path jar = append(localRepositoryPath, filePath);
            try {
                processJarFile(log, jar, messages);
            } catch (IOException e) {
                log.debug("Failed to process JAR file: " + jar + " - " + e.getMessage());
            }
        }
    }

    /**
     * Checks if the given file path represents a JAR file.
     *
     * @param filePath the file path to check
     * @return true if the file is a JAR file, false otherwise
     */
    private static boolean isJarFile(Path filePath) {
        return filePath.toString().toLowerCase().endsWith(JAR_EXTENSION);
    }

    /**
     * Processes a JAR file and extracts log messages from class files.
     *
     * @param log the logger for debug output
     * @param file the JAR file to process
     * @param messages collection to add extracted log messages to
     * @throws IOException if there's an error reading the JAR file
     */
    private static void processJarFile(SimpleLog log, Path file, Collection<LogMessage> messages) throws IOException {
        try (FileSystem zipFs = zipFs(file)) {
            for (Path dir : zipFs.getRootDirectories()) {
                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                                if (file.getFileName().toString().endsWith(CLASS_EXTENSION)) {
                                    try (DataInputStream d = new DataInputStream(Files.newInputStream(file))) {
                                        ClassFile classFile = new ClassFile(d);
                                        handleClass(classFile, messages);
                                    } catch (Exception e) {
                                        // Skip files that cannot be parsed as class files
                                        log.debug("Failed to process class file: " + file + " - " + e.getMessage());
                                    }
                                }
                                return super.visitFile(file, attrs);
                            }
                        }
                );
            }
        }
    }

    /**
     * Handles a class file and extracts log messages if it contains MessageLogger annotations.
     *
     * @param classFile the class file to analyze
     * @param messages collection to add extracted log messages to
     */
    private static void handleClass(final ClassFile classFile, final Collection<LogMessage> messages) {
        AnnotationsAttribute attr = (AnnotationsAttribute) classFile.getAttribute(AnnotationsAttribute.invisibleTag);
        if (attr == null) {
            return;
        }
        for (Annotation annotation : attr.getAnnotations()) {
            if (MESSAGE_LOGGER_ANNOTATION.equals(annotation.getTypeName())) {
                String code = ((StringMemberValue) annotation.getMemberValue("projectCode")).getValue();
                MemberValue lengthValue = annotation.getMemberValue("length");
                int length = lengthValue == null ? DEFAULT_MESSAGE_ID_LENGTH : ((IntegerMemberValue) lengthValue).getValue();
                handleMessageLogger(classFile, code, length, messages);
            }
        }

    }

    /**
     * Handles a message logger class and extracts log messages from its methods.
     *
     * @param classFile the message logger class file
     * @param code the project code prefix for log messages
     * @param length the length for formatting message IDs
     * @param messages collection to add extracted log messages to
     */
    private static void handleMessageLogger(final ClassFile classFile, final String code, final int length, final Collection<LogMessage> messages) {
        for (MethodInfo method : Collections.unmodifiableList(classFile.getMethods())) {
            String logLevel = null;
            String message = null;
            int msgId = -1;
            AnnotationsAttribute attr = (AnnotationsAttribute) method.getAttribute(AnnotationsAttribute.invisibleTag);
            if (attr == null) {
                continue;
            }
            for (Annotation annotation : attr.getAnnotations()) {
                if (LOG_MESSAGE_ANNOTATION.equals(annotation.getTypeName())) {
                    MemberValue level = annotation.getMemberValue("level");
                    logLevel = level == null ? DEFAULT_LOG_LEVEL : ((EnumMemberValue) level).getValue();
                } else if (MESSAGE_ANNOTATION.equals(annotation.getTypeName())) {
                    message = ((StringMemberValue) annotation.getMemberValue("value")).getValue();
                    MemberValue id = annotation.getMemberValue("id");
                    if (id != null) {
                        msgId = ((IntegerMemberValue) id).getValue();
                    }
                }
            }
            if (message != null) {
                LogMessage l = new LogMessage(logLevel, code, message, length, msgId, extractReturnType(method));
                messages.add(l);
            }
        }
    }

    /**
     * Extracts the return type from a method descriptor.
     *
     * @param method the method to analyze
     * @return the return type as a string
     */
    private static String extractReturnType(final MethodInfo method) {
        String descriptor = method.getDescriptor();
        descriptor = descriptor.substring(descriptor.lastIndexOf(")") + 1);
        descriptor = descriptor.replace("/", ".");
        descriptor = descriptor.replace(";", "");
        if (descriptor.startsWith("L")) {
            descriptor = descriptor.substring(1);
        }
        if (VOID_DESCRIPTOR.equals(descriptor)) {
            return VOID_TYPE;
        }
        return descriptor;
    }
    /**
     * Opens a ZIP/JAR file system for the given path.
     *
     * @param path the path to the ZIP/JAR file
     * @return the file system
     * @throws IOException if there's an error opening the file system
     */
    private static FileSystem zipFs(final Path path) throws IOException {
        final Map<String, String> env = new HashMap<>();
        env.put("create", "true");

        // locate file system by using the syntax
        // defined in java.net.JarURLConnection
        URI uri = URI.create("jar:" + path.toUri());
        try {
            return FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException ignore) {
        }
        return FileSystems.newFileSystem(uri, env);
    }
    /**
     * Appends a path to a base path, converting absolute paths to relative.
     *
     * @param base the base path
     * @param toAppend the path to append
     * @return the combined path
     */
    public static Path append(Path base, Path toAppend) {
        if (toAppend.isAbsolute()) {
            toAppend = toAppend.subpath(0, toAppend.getNameCount());
        }
        return base.resolve(toAppend);
    }
}
