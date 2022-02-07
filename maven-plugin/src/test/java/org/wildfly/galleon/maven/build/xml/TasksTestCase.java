/*
 * Copyright 2016-2019 Red Hat, Inc. and/or its affiliates
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
package org.wildfly.galleon.maven.build.xml;

import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.Test;
/**
 *
 * @author jdenise
 */
public class TasksTestCase {

    @Test
    public void testTasks() throws Exception {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Validator validator = null;
        try (Reader r = Files.newBufferedReader(Paths.get("src/main/resources/schema/wildfly-feature-pack-tasks-3_2.xsd"),
                Charset.forName("utf-8"))) {
            Schema schema = schemaFactory.newSchema(new StreamSource(r));
            validator = schema.newValidator();
            try (Reader reader = Files.newBufferedReader(Paths.get("src/test/resources/xml/tasks.xml"), Charset.forName("utf-8"))) {
                validator.validate(new StreamSource(reader));
            }
        }
    }
}
