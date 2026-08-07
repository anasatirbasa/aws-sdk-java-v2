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

import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.utils.Validate;

/**
 * Decodes a DynamoDB Streams {@link Record} into a {@link NormalizedRecord}.
 * Expects {@code NEW_AND_OLD_IMAGES} stream view and ODM-style metadata attributes
 * {@code entityType} and {@code _v} on the item (or supplied defaults).
 */
@SdkPublicApi
public final class StreamRecordDecoder {

    public static final String ENTITY_TYPE_ATTR = "entityType";
    public static final String VERSION_ATTR = "_v";

    private StreamRecordDecoder() {
    }

    public static NormalizedRecord decode(Record streamEvent,
                                          String defaultEntityType,
                                          String partitionKeyAttr,
                                          String sortKeyAttr) {
        Validate.paramNotNull(streamEvent, "streamEvent");
        StreamRecord dynamodb = Validate.paramNotNull(streamEvent.dynamodb(), "dynamodb");

        NormalizedRecord.EventName eventName = toEventName(streamEvent.eventNameAsString());
        Map<String, Object> prev = AttributeValueMaps.fromAttributeMap(dynamodb.oldImage());
        Map<String, Object> next = AttributeValueMaps.fromAttributeMap(dynamodb.newImage());
        if (prev.isEmpty()) {
            prev = null;
        }
        if (next.isEmpty()) {
            next = null;
        }

        Map<String, Object> image = next != null ? next : prev;
        if (image == null) {
            throw new ProjectionException("stream record has neither OldImage nor NewImage");
        }

        String entityType = stringAttr(image, ENTITY_TYPE_ATTR);
        if (entityType == null) {
            entityType = defaultEntityType;
        }
        String version = stringAttr(image, VERSION_ATTR);
        if (version == null) {
            throw new ProjectionException("stream record is missing required version attribute " + VERSION_ATTR);
        }

        String sourceItemKey = composeSourceItemKey(dynamodb.keys(), partitionKeyAttr, sortKeyAttr);
        return NormalizedRecord.builder()
                               .entityType(entityType)
                               .eventName(eventName)
                               .prev(prev)
                               .next(next)
                               .sourceItemKey(sourceItemKey)
                               .sourceVersion(version)
                               .eventId(streamEvent.eventID())
                               .build();
    }

    /**
     * Helper for tests and synthetic apply paths that are not backed by a real stream record.
     */
    public static NormalizedRecord insert(String entityType,
                                          String sourceItemKey,
                                          Map<String, Object> next) {
        Map<String, Object> item = new java.util.LinkedHashMap<>(next);
        item.putIfAbsent(ENTITY_TYPE_ATTR, entityType);
        item.putIfAbsent(VERSION_ATTR, VersionGenerator.next());
        return NormalizedRecord.builder()
                               .entityType(entityType)
                               .eventName(NormalizedRecord.EventName.INSERT)
                               .next(item)
                               .sourceItemKey(sourceItemKey)
                               .sourceVersion(String.valueOf(item.get(VERSION_ATTR)))
                               .eventId("synthetic-" + sourceItemKey)
                               .build();
    }

    public static NormalizedRecord modify(String entityType,
                                          String sourceItemKey,
                                          Map<String, Object> prev,
                                          Map<String, Object> next) {
        Map<String, Object> nextItem = new java.util.LinkedHashMap<>(next);
        nextItem.putIfAbsent(ENTITY_TYPE_ATTR, entityType);
        nextItem.putIfAbsent(VERSION_ATTR, VersionGenerator.next());
        Map<String, Object> prevItem = new java.util.LinkedHashMap<>(prev);
        return NormalizedRecord.builder()
                               .entityType(entityType)
                               .eventName(NormalizedRecord.EventName.MODIFY)
                               .prev(prevItem)
                               .next(nextItem)
                               .sourceItemKey(sourceItemKey)
                               .sourceVersion(String.valueOf(nextItem.get(VERSION_ATTR)))
                               .eventId("synthetic-modify-" + sourceItemKey)
                               .build();
    }

    public static NormalizedRecord remove(String entityType,
                                          String sourceItemKey,
                                          Map<String, Object> prev) {
        Map<String, Object> prevItem = new java.util.LinkedHashMap<>(prev);
        prevItem.putIfAbsent(ENTITY_TYPE_ATTR, entityType);
        prevItem.putIfAbsent(VERSION_ATTR, VersionGenerator.next());
        return NormalizedRecord.builder()
                               .entityType(entityType)
                               .eventName(NormalizedRecord.EventName.REMOVE)
                               .prev(prevItem)
                               .sourceItemKey(sourceItemKey)
                               .sourceVersion(String.valueOf(prevItem.get(VERSION_ATTR)))
                               .eventId("synthetic-remove-" + sourceItemKey)
                               .build();
    }

    private static NormalizedRecord.EventName toEventName(String eventName) {
        if (eventName == null) {
            throw new ProjectionException("stream eventName is null");
        }
        switch (eventName) {
            case "INSERT":
                return NormalizedRecord.EventName.INSERT;
            case "MODIFY":
                return NormalizedRecord.EventName.MODIFY;
            case "REMOVE":
                return NormalizedRecord.EventName.REMOVE;
            default:
                throw new ProjectionException("unsupported stream eventName: " + eventName);
        }
    }

    private static String composeSourceItemKey(Map<String, AttributeValue> keys,
                                               String partitionKeyAttr,
                                               String sortKeyAttr) {
        if (keys == null || keys.isEmpty()) {
            throw new ProjectionException("stream record Keys map is empty");
        }
        AttributeValue pk = keys.get(partitionKeyAttr);
        if (pk == null && keys.size() == 1) {
            pk = keys.values().iterator().next();
        }
        if (pk == null) {
            throw new ProjectionException("missing partition key " + partitionKeyAttr + " in stream Keys");
        }
        StringBuilder sb = new StringBuilder(stringFromKey(pk));
        if (sortKeyAttr != null && keys.containsKey(sortKeyAttr)) {
            sb.append('#').append(stringFromKey(keys.get(sortKeyAttr)));
        }
        return sb.toString();
    }

    private static String stringFromKey(AttributeValue value) {
        if (value.s() != null) {
            return value.s();
        }
        if (value.n() != null) {
            return value.n();
        }
        throw new ProjectionException("unsupported key AttributeValue type");
    }

    private static String stringAttr(Map<String, Object> item, String name) {
        Object v = item.get(name);
        return v == null ? null : String.valueOf(v);
    }
}
