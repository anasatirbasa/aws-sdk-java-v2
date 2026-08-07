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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.Validate;

/**
 * LRU-bounded cache wrapper around a {@link ParentResolver} for backfill workloads.
 */
@SdkPublicApi
public final class CachingParentResolver implements ParentResolver {

    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final ParentResolver delegate;
    private final int maxEntries;
    private final Map<String, Map<String, Object>> cache =
        Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return size() > maxEntries;
            }
        });

    public CachingParentResolver(ParentResolver delegate) {
        this(delegate, DEFAULT_MAX_ENTRIES);
    }

    public CachingParentResolver(ParentResolver delegate, int maxEntries) {
        this.delegate = Validate.paramNotNull(delegate, "delegate");
        this.maxEntries = maxEntries <= 0 ? DEFAULT_MAX_ENTRIES : maxEntries;
    }

    @Override
    public Map<String, Object> findParent(String joinKeyValue) {
        Map<String, Object> cached = cache.get(joinKeyValue);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> parent = delegate.findParent(joinKeyValue);
        if (parent != null) {
            cache.put(joinKeyValue, parent);
        }
        return parent;
    }

    public void invalidate(String joinKeyValue) {
        cache.remove(joinKeyValue);
    }

    public void clear() {
        cache.clear();
    }
}
