package software.amazon.awssdk.enhanced.dynamodb.internal.converter.string;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.StringConverter;
import software.amazon.awssdk.enhanced.dynamodb.internal.converter.StringConverterProvider;
import software.amazon.awssdk.enhanced.dynamodb.model.Record;

@RunWith(MockitoJUnitRunner.class)
public class FallbackStringConverterProviderTest {

    @Mock
    private StringConverterProvider mockDelegate;

    private FallbackStringConverterProvider fallbackProvider;

    @Before
    public void setUp() {
        fallbackProvider = new FallbackStringConverterProvider(mockDelegate);
    }

    @Test
    public void testUsesDelegateWhenAvailable() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        StringConverter<Record> mockConverter = mock(StringConverter.class);
        when(mockConverter.toString(any())).thenReturn("mock-serialized-result");
        when(mockDelegate.converterFor(type)).thenReturn(mockConverter);

        StringConverter<Record> result = fallbackProvider.converterFor(type);
        assertEquals("mock-serialized-result", result.toString(createRecord()));
        verify(mockDelegate).converterFor(type);
    }

    @Test
    public void testFallbackSerializationDeserialization() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        when(mockDelegate.converterFor(type)).thenThrow(new IllegalArgumentException("Not found"));

        StringConverter<Record> converter = fallbackProvider.converterFor(type);
        Record original = createRecord();
        String json = converter.toString(original);
        Record parsed = converter.fromString(json);

        assertNotNull(json);
        assertNotNull(parsed);
        assertEquals(original.getId(), parsed.getId());
        assertEquals(original.getAttributesMap(), parsed.getAttributesMap());
    }

    @Test
    public void testFallbackCaching() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        when(mockDelegate.converterFor(type)).thenThrow(new IllegalArgumentException("Not found"));

        StringConverter<Record> first = fallbackProvider.converterFor(type);
        StringConverter<Record> second = fallbackProvider.converterFor(type);

        assertSame(first, second);
    }

    @Test
    public void testFallbackHandlesNull() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        when(mockDelegate.converterFor(type)).thenThrow(new IllegalArgumentException("Not found"));

        StringConverter<Record> converter = fallbackProvider.converterFor(type);
        assertNull(converter.toString(null));
        assertNull(converter.fromString(null));
    }

    @Test
    public void testFallbackReturnsNullOnMalformedJson() {
        EnhancedType<Record> type = EnhancedType.of(Record.class);
        when(mockDelegate.converterFor(type)).thenThrow(new IllegalArgumentException("Not found"));

        StringConverter<Record> converter = fallbackProvider.converterFor(type);
        assertNull(converter.fromString("{invalid"));
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
