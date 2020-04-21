/*
 * Copyright 2016-2020 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.plugin.transformer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 *
 * @author jdenise
 */
public class JakartaTransformer {

    static class WrappedInputStream extends FileInputStream {

        private final Path dir;

        public WrappedInputStream(File file, Path dir) throws FileNotFoundException {
            super(file);
            this.dir = dir;
        }

        @Override
        public void close() throws IOException {
            super.close();
            Utils.recursiveDelete(dir);
        }

    }

    public interface LogHandler {

        void print(String format, Object... args);
    }

    public static final String TRANSFORM_ARTIFACTS = "jakarta.transform.artifacts";

    private static final LogHandler DEFAULT_LOG_HANDLER = new LogHandler() {
        @Override
        public void print(String format, Object... args) {
            System.out.println(String.format(format, args));
        }
    };

    // POC entry point to use galleon-plugins to transform artifacts offline.
    public static void main(String[] args) throws Exception {
        EclipseTransformer.transform(args, false);
    }

    public static InputStream transform(InputStream in, String name, boolean verbose, LogHandler log) throws IOException {
        if (log == null) {
            log = DEFAULT_LOG_HANDLER;
        }
        if (!name.endsWith(".war") && !name.endsWith(".ear")
                && !name.endsWith(".rar") && !name.endsWith(".jar") && !name.endsWith(".xml")) {
            name = name + ".war";
        }
        Path dir = Files.createTempDirectory("jakarta-transform");
        Path src = dir.resolve("jakartaee-" + name);
        Path target = dir.resolve(name);

        FileOutputStream out = new FileOutputStream(src.toFile());
        byte[] buffer = new byte[1024];
        int size;
        while ((size = in.read(buffer)) != -1) {
            out.write(buffer, 0, size);
        }
        out.close();
        EclipseTransformer.transform(src, target, verbose, log);
        return new WrappedInputStream(target.toFile(), dir);
    }

    public static TransformedArtifact transform(Path src, Path target, boolean verbose, LogHandler log) throws IOException {
        if (log == null) {
            log = DEFAULT_LOG_HANDLER;
        }
        Path originalSrc = src;
        Path actualTarget = null;
        Path safeCopy = null;
        boolean failed = false;
        boolean isExploded = false;
        if (Files.isDirectory(src)) {
            // Exploded
            isExploded = true;
            if (target.equals(src)) {
                safeCopy = target.getParent().resolve("jakartaee-" + target.getFileName().toString());
                log.print("Transformation target is equal to src, moving src artifact directory to %s", safeCopy);
                Utils.copy(src, safeCopy);
                Utils.recursiveDelete(src);
                src = safeCopy;
            }
            actualTarget = target;
        } else {
            if (Files.isDirectory(target)) {
                actualTarget = target.resolve(src.getFileName());
            } else {
                // target and src are the same file.
                if (src.equals(target)) {
                    safeCopy = target.getParent().resolve("jakartaee-" + target.getFileName().toString());
                    log.print("Transformation target is equal to src, moving src artifact to %s", safeCopy);
                    if (Files.exists(safeCopy)) {
                        Files.delete(safeCopy);
                    }
                    Files.copy(src, safeCopy);
                    Files.delete(src);
                    src = safeCopy;
                }
                actualTarget = target;
            }
        }
        if (Files.exists(actualTarget)) {
            throw new IOException("Transformation target " + actualTarget + " already exist");
        }
        try {
            return EclipseTransformer.transform(src, actualTarget, verbose, log);
        } catch (Throwable ex) {
            failed = true;
            if (ex instanceof IOException) {
                throw (IOException) ex;
            } else {
                throw new IOException(ex);
            }
        } finally {
            if (failed) {
                // revert
                if (safeCopy != null) {
                    log.print("Exception occured, reverting original src artifact");
                    if (isExploded) {
                        Utils.copy(safeCopy, originalSrc);
                    } else {
                        Files.copy(safeCopy, originalSrc);
                    }
                }
            }
            if (safeCopy != null) {
                if (isExploded) {
                    Utils.recursiveDelete(safeCopy);
                } else {
                    Files.delete(safeCopy);
                }
            }
        }
    }

}
