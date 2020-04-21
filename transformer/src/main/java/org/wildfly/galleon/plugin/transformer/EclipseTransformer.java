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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.transformer.action.Changes;
import org.eclipse.transformer.Transformer;
import org.wildfly.galleon.plugin.transformer.JakartaTransformer.LogHandler;

/**
 *
 * @author jdenise
 */
public class EclipseTransformer {

    public static final String DEFAULT_RENAMES_REFERENCE = "jakarta-renames.properties";
    public static final String DEFAULT_MASTER_TXT_REFERENCE = "jakarta-txt-master.properties";
    public static final String DEFAULT_PER_CLASS_DIRECT_REFERENCE = "jakarta-per-class.properties";
    public static final String DEFAULT_DIRECT_REFERENCE = "jakarta-direct.properties";

    private static Map<Transformer.AppOption, String> getOptionDefaults() {
        HashMap<Transformer.AppOption, String> optionDefaults
                = new HashMap<Transformer.AppOption, String>();

        optionDefaults.put(Transformer.AppOption.RULES_RENAMES, DEFAULT_RENAMES_REFERENCE);
        optionDefaults.put(Transformer.AppOption.RULES_MASTER_TEXT, DEFAULT_MASTER_TXT_REFERENCE);
        optionDefaults.put(Transformer.AppOption.RULES_PER_CLASS_CONSTANT, DEFAULT_PER_CLASS_DIRECT_REFERENCE);
        optionDefaults.put(Transformer.AppOption.RULES_DIRECT, DEFAULT_DIRECT_REFERENCE);

        return optionDefaults;
    }

    static TransformedArtifact transform(Path src, Path target, boolean verbose, LogHandler log) throws IOException {
        boolean transformed = false;
        boolean signed = false;
        boolean unsigned = false;
        try {
            if (!verbose) {
                // Disable all logging that is very verbose.
                System.setProperty("org.slf4j.simpleLogger.log.Transformer", "error");
            }
            List<String> args = new ArrayList<>();
            args.add(src.toString());
            args.add(target.toString());
            if (verbose) {
                args.add("-v");
            } else {
                args.add("--quiet");
            }
            //args.add("--type");
            //args.add(getType(src));
            String[] array = new String[args.size()];
            transformed = transform(args.toArray(array), true);
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

    // Call Eclipse transformer
    static boolean transform(String[] args, boolean silent) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Transformer jTrans = null;
        if (silent) {
            jTrans = new Transformer(new java.io.PrintStream(out), new java.io.PrintStream(out));
        } else {
            jTrans = new Transformer(System.out, System.err);
        }
        jTrans.setOptionDefaults(EclipseTransformer.class, getOptionDefaults());
        jTrans.setArgs(args);

        @SuppressWarnings("unused")
        int rc = jTrans.run();
        if (rc != 0) {
            throw new Exception("Eror occured during transformation. Error code " + rc);
        }
        // New API needed in eclipse transformer.
        Changes changes = jTrans.getLastActiveChanges();
        if (changes != null) {
            return changes.hasChanges();
        }
        return false;
    }

//    private static String getType(Path src) {
//       if(Files.isDirectory(src)) {
//           retun
//       }
//    }
}
