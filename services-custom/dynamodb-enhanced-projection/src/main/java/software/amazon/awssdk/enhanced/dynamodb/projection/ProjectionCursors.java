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
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.dynamodb.projection;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.utils.Validate;

/**
 * Opaque pagination cursors, matching the JS ODM pattern ({@code LastEvaluatedKey} ↔ base64url).
 *
 * <ul>
 *   <li>{@link #encodeOffset}/{@link #decodeOffset} — in-memory HAVING/ORDER BY pages
 *       ({@link SummaryQueryEngine})</li>
 *   <li>{@link #encodeExclusiveStartKey}/{@link #decodeExclusiveStartKey} — native DynamoDB
 *       Query/Scan pagination over the summary table (key order only)</li>
 * </ul>
 */
@SdkPublicApi
public final class ProjectionCursors {

    private ProjectionCursors() {
    }

    public static String encodeOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        String json = "{\"o\":" + offset + "}";
        return base64UrlEncode(json);
    }

    public static int decodeOffset(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        String json = base64UrlDecode(cursor);
        // Minimal parse: {"o":N}
        int idx = json.indexOf("\"o\"");
        if (idx < 0) {
            throw new ProjectionException("invalid offset cursor");
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            throw new ProjectionException("invalid offset cursor");
        }
        StringBuilder num = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '-' || Character.isDigit(c)) {
                num.append(c);
            } else if (!Character.isWhitespace(c) && c != ',') {
                if (c == '}') {
                    break;
                }
                if (num.length() > 0) {
                    break;
                }
            }
        }
        try {
            return Integer.parseInt(num.toString().trim());
        } catch (NumberFormatException e) {
            throw new ProjectionException("invalid offset cursor", e);
        }
    }

    /**
     * Encode a DynamoDB exclusive start key as an opaque cursor (ODM-style).
     * Only string and number attribute values are supported in this PoC.
     */
    public static String encodeExclusiveStartKey(Map<String, AttributeValue> key) {
        Validate.paramNotNull(key, "key");
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, AttributeValue> e : key.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            AttributeValue v = e.getValue();
            if (v.s() != null) {
                sb.append("{\"S\":\"").append(escape(v.s())).append("\"}");
            } else if (v.n() != null) {
                sb.append("{\"N\":\"").append(escape(v.n())).append("\"}");
            } else {
                throw new ProjectionException("cursor key supports only S/N AttributeValue types");
            }
        }
        sb.append('}');
        return base64UrlEncode(sb.toString());
    }

    public static Map<String, AttributeValue> decodeExclusiveStartKey(String cursor) {
        Validate.paramNotBlank(cursor, "cursor");
        String json = base64UrlDecode(cursor);
        Map<String, AttributeValue> out = new LinkedHashMap<>();
        // Very small PoC parser for {"attr":{"S":"x"}} or {"attr":{"N":"1"}}
        int i = 0;
        if (json.isEmpty() || json.charAt(0) != '{') {
            throw new ProjectionException("invalid DynamoDB cursor");
        }
        i = 1;
        while (i < json.length()) {
            while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) {
                i++;
            }
            if (i < json.length() && json.charAt(i) == '}') {
                break;
            }
            if (i >= json.length() || json.charAt(i) != '"') {
                throw new ProjectionException("invalid DynamoDB cursor");
            }
            int keyEnd = json.indexOf('"', i + 1);
            String attr = unescape(json.substring(i + 1, keyEnd));
            i = keyEnd + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != ':') {
                throw new ProjectionException("invalid DynamoDB cursor");
            }
            i++;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (json.startsWith("{\"S\":\"", i)) {
                i += 6;
                int valEnd = findClosingQuote(json, i);
                String s = unescape(json.substring(i, valEnd));
                out.put(attr, AttributeValue.builder().s(s).build());
                i = valEnd + 1;
                // After the value's closing quote, expect '}' closing the {"S":"..."} object.
                if (i >= json.length() || json.charAt(i) != '}') {
                    throw new ProjectionException("invalid DynamoDB cursor");
                }
                i++;
            } else if (json.startsWith("{\"N\":\"", i)) {
                i += 6;
                int valEnd = findClosingQuote(json, i);
                String n = unescape(json.substring(i, valEnd));
                out.put(attr, AttributeValue.builder().n(n).build());
                i = valEnd + 1;
                if (i >= json.length() || json.charAt(i) != '}') {
                    throw new ProjectionException("invalid DynamoDB cursor");
                }
                i++;
            } else {
                throw new ProjectionException("invalid DynamoDB cursor");
            }
        }
        return out;
    }

    private static int findClosingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            if (json.charAt(i) == '"' && (i == from || json.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        throw new ProjectionException("invalid DynamoDB cursor");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String base64UrlEncode(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                     .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlDecode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ProjectionException("invalid cursor encoding", e);
        }
    }
}
