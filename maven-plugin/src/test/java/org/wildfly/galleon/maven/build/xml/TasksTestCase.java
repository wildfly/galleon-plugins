/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
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
