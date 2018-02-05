/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.testframework.xml;

import java.io.File;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

import org.xml.sax.InputSource;

public class TestSuiteParser {
    public TestSuiteParser() {
    }

    public org.apache.asterix.testframework.xml.TestSuite parse(File testSuiteCatalog) throws Exception {
        SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
        saxParserFactory.setNamespaceAware(true);
        saxParserFactory.setXIncludeAware(true);
        SAXParser saxParser = saxParserFactory.newSAXParser();
        saxParser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "file");

        JAXBContext ctx = JAXBContext.newInstance(org.apache.asterix.testframework.xml.TestSuite.class);
        Unmarshaller um = ctx.createUnmarshaller();
        return (org.apache.asterix.testframework.xml.TestSuite) um.unmarshal(
                new SAXSource(saxParser.getXMLReader(), new InputSource(testSuiteCatalog.toURI().toString())));
    }
}
