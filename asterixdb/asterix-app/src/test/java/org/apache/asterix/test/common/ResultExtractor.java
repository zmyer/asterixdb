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
package org.apache.asterix.test.common;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Iterators;

/**
 * extracts results from the response of the QueryServiceServlet.
 * As the response is not necessarily valid JSON, non-JSON content has to be extracted in some cases.
 * The current implementation creates a too many copies of the data to be usable for larger results.
 */
public class ResultExtractor {

    private enum ResultField {
        RESULTS("results"),
        REQUEST_ID("requestID"),
        METRICS("metrics"),
        CLIENT_CONTEXT_ID("clientContextID"),
        SIGNATURE("signature"),
        STATUS("status"),
        TYPE("type"),
        ERRORS("errors");

        private static final Map<String, ResultField> fields = new HashMap<>();

        static {
            for (ResultField field : ResultField.values()) {
                fields.put(field.getFieldName(), field);
            }
        }

        private String fieldName;

        ResultField(String fieldName) {
            this.fieldName = fieldName;
        }

        public String getFieldName() {
            return fieldName;
        }

        public static ResultField ofFieldName(String fieldName) {
            return fields.get(fieldName);
        }
    }

    private static final Logger LOGGER = LogManager.getLogger();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static InputStream extract(InputStream resultStream) throws Exception {
        return extract(resultStream, EnumSet.of(ResultField.RESULTS));
    }

    public static InputStream extractMetrics(InputStream resultStream) throws Exception {
        return extract(resultStream, EnumSet.of(ResultField.METRICS));
    }

    public static String extractHandle(InputStream resultStream) throws Exception {
        String result = IOUtils.toString(resultStream, StandardCharsets.UTF_8);
        ObjectNode resultJson = OBJECT_MAPPER.readValue(result, ObjectNode.class);
        final JsonNode handle = resultJson.get("handle");
        if (handle != null) {
            return handle.asText();
        } else {
            JsonNode errors = resultJson.get("errors");
            if (errors != null) {
                JsonNode msg = errors.get(0).get("msg");
                throw new AsterixException(msg.asText());
            }
        }
        return null;
    }

    private static InputStream extract(InputStream resultStream, EnumSet<ResultField> resultFields) throws Exception {
        final String resultStr = IOUtils.toString(resultStream, Charset.defaultCharset());
        final PrettyPrinter singleLine = new SingleLinePrettyPrinter();
        final ObjectNode result = OBJECT_MAPPER.readValue(resultStr, ObjectNode.class);

        LOGGER.debug("+++++++\n" + result + "\n+++++++\n");
        // if we have errors field in the results, we will always return it
        checkForErrors(result);
        final StringBuilder resultBuilder = new StringBuilder();
        for (Iterator<String> fieldNameIter = result.fieldNames(); fieldNameIter.hasNext();) {
            final String fieldName = fieldNameIter.next();
            final ResultField fieldKind = ResultField.ofFieldName(fieldName.split("-")[0]);
            if (fieldKind == null) {
                throw new AsterixException("Unanticipated field \"" + fieldName + "\"");
            }
            if (!resultFields.contains(fieldKind)) {
                continue;
            }
            final JsonNode fieldValue = result.get(fieldName);
            switch (fieldKind) {
                case RESULTS:
                    if (fieldValue.size() <= 1) {
                        if (fieldValue.size() == 0) {
                            resultBuilder.append("");
                        } else if (fieldValue.isArray()) {
                            if (fieldValue.get(0).isTextual()) {
                                resultBuilder.append(fieldValue.get(0).asText());
                            } else {
                                ObjectMapper omm = new ObjectMapper();
                                omm.setDefaultPrettyPrinter(singleLine);
                                omm.enable(SerializationFeature.INDENT_OUTPUT);
                                resultBuilder.append(omm.writer(singleLine).writeValueAsString(fieldValue));
                            }
                        } else {
                            resultBuilder.append(OBJECT_MAPPER.writeValueAsString(fieldValue));
                        }
                    } else {
                        JsonNode[] fields = Iterators.toArray(fieldValue.elements(), JsonNode.class);
                        if (fields.length > 1) {
                            for (JsonNode f : fields) {
                                if (f.isObject()) {

                                    resultBuilder.append(OBJECT_MAPPER.writeValueAsString(f));
                                } else {
                                    resultBuilder.append(f.asText());
                                }
                            }
                        }

                    }
                    break;
                case REQUEST_ID:
                case METRICS:
                case CLIENT_CONTEXT_ID:
                case SIGNATURE:
                case STATUS:
                case TYPE:
                    resultBuilder.append(OBJECT_MAPPER.writeValueAsString(fieldValue));
                    break;
                default:
                    throw new IllegalStateException("Unexpected result field: " + fieldKind);
            }
        }
        return IOUtils.toInputStream(resultBuilder.toString(), StandardCharsets.UTF_8);
    }

    private static void checkForErrors(ObjectNode result) throws AsterixException {
        final JsonNode errorsField = result.get(ResultField.ERRORS.getFieldName());
        if (errorsField != null) {
            final JsonNode errors = errorsField.get(0).get("msg");
            if (!result.get(ResultField.METRICS.getFieldName()).has("errorCount")) {
                throw new AsterixException("Request reported error but not an errorCount");
            }
            throw new AsterixException(errors.asText());
        }
    }
}