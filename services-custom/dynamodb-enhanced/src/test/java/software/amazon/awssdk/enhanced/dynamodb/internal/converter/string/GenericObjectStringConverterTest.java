package software.amazon.awssdk.enhanced.dynamodb.internal.converter.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import org.junit.Test;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.StringConverter;
import software.amazon.awssdk.enhanced.dynamodb.model.Record;

public class GenericObjectStringConverterTest {

    @Test
    public void testSerializeAndDeserializeCustomObject() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        StringConverter<Record> converter = GenericObjectStringConverter.create(type);

        Record original = createRecord();
        String json = converter.toString(original);
        Record result = converter.fromString(json);

        assertNotNull(json);
        assertNotNull(result);
        assertEquals(original.getId(), result.getId());
        assertEquals(original.getAttributesMap(), result.getAttributesMap());
    }

    @Test
    public void serializeNullObject_returnsNull() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        StringConverter<Record> converter = GenericObjectStringConverter.create(type);

        assertNull(converter.toString(null));
    }

    @Test
    public void serializeNullJsonString_returnsNull() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        StringConverter<Record> converter = GenericObjectStringConverter.create(type);

        assertNull(converter.fromString(null));
    }

    @Test
    public void deserializeMalformedJson_returnsNull() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        StringConverter<Record> converter = GenericObjectStringConverter.create(type);

        assertNull(converter.fromString("{malformed"));
    }

    private Record createRecord() {
        return new Record()
            .setId("123")
            .setAttributesMap(new HashMap<String, String>() {{
                put("mapAttribute1", "mapValue1");
                put("mapAttribute2", "mapValue2");
                put("mapAttribute3", "mapValue3");
            }});
    }
}
