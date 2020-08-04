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
import java.io.IOException;
import java.nio.file.Path;

import org.wildfly.galleon.plugin.transformer.JakartaTransformer.LogHandler;
import org.wildfly.extras.transformer.TransformerFactory;

/**
 *
 * @author jdenise
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public class BataviaTransformer {

    static TransformedArtifact transform(Path src, Path target, boolean verbose, LogHandler log) throws IOException {
        boolean transformed;
        boolean signed = false;
        boolean unsigned = false;
        try {
            transformed = transform(src.toString(), target.toString(), false);
            // TODO Only check for jar not for exploded for now
            if (src.getFileName().toString().endsWith(".jar")) {
                signed = JarUtils.isSignedJar(src);
            }
            if (transformed) {
                log.print("EE9: transformed %s", target.getFileName().toString());
            }
            if (signed && transformed) {
                log.print("WARNING: EE9: unsigning transformed %s ", target.getFileName().toString());
                JarUtils.unsign(target);
                unsigned = true;
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return new TransformedArtifact(src, target, transformed, signed, unsigned);
    }

    static boolean transform(final String source, final String target, boolean verbose) throws Exception {
        return TransformerFactory.getInstance().newTransformer().setVerbose(verbose).build().transform(new File(source), new File(target));
    }

}
