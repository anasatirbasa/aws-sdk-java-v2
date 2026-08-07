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

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.annotations.SdkProtectedApi;

/**
 * Lexicographically sortable version generator (ULID-inspired) for source/target {@code _v}.
 * Crockford Base32 encoding of timestamp + randomness so string {@code <} comparisons work
 * for the version-map condition.
 */
@SdkProtectedApi
public final class VersionGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong LAST_TIMESTAMP = new AtomicLong(0);

    private VersionGenerator() {
    }

    public static String next() {
        long now = System.currentTimeMillis();
        long last;
        do {
            last = LAST_TIMESTAMP.get();
            if (now <= last) {
                now = last + 1;
            }
        } while (!LAST_TIMESTAMP.compareAndSet(last, now));

        char[] chars = new char[26];
        encodeTime(now, chars);
        encodeRandom(chars);
        return new String(chars);
    }

    private static void encodeTime(long timestamp, char[] chars) {
        for (int i = 9; i >= 0; i--) {
            chars[i] = ENCODING[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
    }

    private static void encodeRandom(char[] chars) {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFFL);
        }
        for (int i = 25; i >= 10; i--) {
            chars[i] = ENCODING[(int) (value & 0x1F)];
            value >>>= 5;
        }
    }
}
