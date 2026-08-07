/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.utils.IoUtils;

/**
 * Loads canonical benchmark scenario keys from {@code /benchmark-scenarios.json}.
 */
public final class BenchmarkScenarioCatalog {

    private static final String RESOURCE = "/benchmark-scenarios.json";

    private BenchmarkScenarioCatalog() {
    }

    public static List<String> scenarioKeys() {
        return load().keys;
    }

    public static Map<String, String> legacyAliases() {
        return load().legacyAliases;
    }

    public static ScenarioMetadata metadata(String scenarioKey) {
        ScenarioMetadata metadata = load().metadata.get(scenarioKey);
        if (metadata == null) {
            throw new IllegalArgumentException("unknown benchmark scenario " + scenarioKey);
        }
        return metadata;
    }

    public static Map<String, ScenarioMetadata> metadataByKey() {
        return load().metadata;
    }

    public static int expectedCount() {
        return 35;
    }

    private static Catalog load() {
        String externalCatalog = System.getenv("BENCHMARK_SCENARIO_CATALOG");
        if (externalCatalog != null && !externalCatalog.isEmpty()) {
            try {
                return parse(new String(Files.readAllBytes(Paths.get(externalCatalog)), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("failed to load benchmark scenario catalog " + externalCatalog, e);
            }
        }
        try (InputStream in = BenchmarkScenarioCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            String json = IoUtils.toUtf8String(in);
            return parse(json);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + RESOURCE, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Catalog parse(String json) {
        // Minimal JSON parse for fixed catalog shape (avoid adding Jackson dependency to test code).
        List<String> keys = new ArrayList<>();
        Map<String, ScenarioMetadata> metadata = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        int idx = 0;
        while ((idx = json.indexOf("\"key\"", idx)) >= 0) {
            int objectStart = json.lastIndexOf('{', idx);
            int objectEnd = json.indexOf('}', idx);
            int colon = json.indexOf(':', idx);
            int q1 = json.indexOf('"', colon + 1);
            int q2 = json.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) {
                String key = json.substring(q1 + 1, q2);
                keys.add(key);
                String object = objectStart >= 0 && objectEnd > objectStart
                                ? json.substring(objectStart, objectEnd + 1) : "";
                metadata.put(key, new ScenarioMetadata(key,
                                                       fieldOr(object, "name", key),
                                                       fieldOr(object, "category", "Unclassified"),
                                                       fieldOr(object, "workload", key),
                                                       fieldOr(object, "enhancedQueriesPath", "See benchmark catalog"),
                                                       fieldOr(object, "streamProjectionsPath", "See benchmark catalog"),
                                                       fieldOr(object, "expectedResult", "See benchmark catalog")));
            }
            idx = q2 + 1;
        }
        int aliasStart = json.indexOf("\"legacyAliases\"");
        if (aliasStart >= 0) {
            int brace = json.indexOf('{', aliasStart);
            int end = json.indexOf('}', brace);
            String block = json.substring(brace + 1, end);
            String[] pairs = block.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    aliases.put(stripQuotes(kv[0].trim()), stripQuotes(kv[1].trim()));
                }
            }
        }
        return new Catalog(Collections.unmodifiableList(keys),
                           Collections.unmodifiableMap(metadata),
                           Collections.unmodifiableMap(aliases));
    }

    private static String field(String object, String fieldName) {
        int fieldStart = object.indexOf("\"" + fieldName + "\"");
        if (fieldStart < 0) {
            return "";
        }
        int colon = object.indexOf(':', fieldStart);
        int firstQuote = object.indexOf('"', colon + 1);
        int secondQuote = object.indexOf('"', firstQuote + 1);
        return firstQuote >= 0 && secondQuote > firstQuote
               ? object.substring(firstQuote + 1, secondQuote) : "";
    }

    private static String fieldOr(String object, String fieldName, String defaultValue) {
        String value = field(object, fieldName);
        return value.isEmpty() ? defaultValue : value;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static final class Catalog {
        private final List<String> keys;
        private final Map<String, ScenarioMetadata> metadata;
        private final Map<String, String> legacyAliases;

        private Catalog(List<String> keys,
                        Map<String, ScenarioMetadata> metadata,
                        Map<String, String> legacyAliases) {
            this.keys = keys;
            this.metadata = metadata;
            this.legacyAliases = legacyAliases;
        }
    }

    public static final class ScenarioMetadata {
        private final String key;
        private final String name;
        private final String category;
        private final String workload;
        private final String enhancedQueriesPath;
        private final String streamProjectionsPath;
        private final String expectedResult;

        private ScenarioMetadata(String key,
                                 String name,
                                 String category,
                                 String workload,
                                 String enhancedQueriesPath,
                                 String streamProjectionsPath,
                                 String expectedResult) {
            this.key = key;
            this.name = name;
            this.category = category;
            this.workload = workload;
            this.enhancedQueriesPath = enhancedQueriesPath;
            this.streamProjectionsPath = streamProjectionsPath;
            this.expectedResult = expectedResult;
        }

        public String key() {
            return key;
        }

        public String name() {
            return name;
        }

        public String category() {
            return category;
        }

        public String workload() {
            return workload;
        }

        public String enhancedQueriesPath() {
            return enhancedQueriesPath;
        }

        public String streamProjectionsPath() {
            return streamProjectionsPath;
        }

        public String expectedResult() {
            return expectedResult;
        }
    }
}
