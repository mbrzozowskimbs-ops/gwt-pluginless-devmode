package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gwt.dev.shell.BrowserChannel.JavaObjectRef;
import com.google.gwt.dev.shell.BrowserChannel.JsObjectRef;
import com.google.gwt.dev.shell.BrowserChannel.Value;

@DisplayName("ValueCodec")
class ValueCodecTest {

    @Test
    @DisplayName("Should decode JSON scalars to the matching BrowserChannel types")
    void shouldDecodeScalars() throws Exception {
        assertThat(ValueCodec.decode(null).isNull()).isTrue();
        assertThat(ValueCodec.decode(true).getBoolean()).isTrue();
        assertThat(ValueCodec.decode(42L).getInt()).isEqualTo(42);
        assertThat(ValueCodec.decode(2.5).getDouble()).isEqualTo(2.5);
        assertThat(ValueCodec.decode("tekst").getString()).isEqualTo("tekst");
    }

    @Test
    @DisplayName("Should decode integral numbers outside the int range as doubles")
    void shouldDecodeLargeIntegersAsDouble() throws Exception {
        // when
        Value value = ValueCodec.decode(3_000_000_000L);

        // then
        assertThat(value.isDouble()).isTrue();
        assertThat(value.getDouble()).isEqualTo(3.0E9);
    }

    @Test
    @DisplayName("Should decode tagged values")
    void shouldDecodeTaggedValues() throws Exception {
        assertThat(ValueCodec.decode(List.of("u")).isUndefined()).isTrue();
        assertThat(ValueCodec.decode(List.of("d", "NaN")).getDouble()).isNaN();
        assertThat(ValueCodec.decode(List.of("d", "-Infinity")).getDouble()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(ValueCodec.decode(List.of("j", 7L)).getJavaObject().getRefid()).isEqualTo(7);
        assertThat(ValueCodec.decode(List.of("o", 9L)).getJsObject().getRefid()).isEqualTo(9);
    }

    @Test
    @DisplayName("Should reject JSON that is not a value")
    void shouldRejectInvalidValues() {
        assertThatThrownBy(() -> ValueCodec.decode(List.of("x", 1L))).isInstanceOf(BridgeProtocolException.class);
        assertThatThrownBy(() -> ValueCodec.decode(List.of())).isInstanceOf(BridgeProtocolException.class);
        assertThatThrownBy(() -> ValueCodec.decode(List.of("d", "1"))).isInstanceOf(BridgeProtocolException.class);
        assertThatThrownBy(() -> ValueCodec.decode(List.of("j", "1"))).isInstanceOf(BridgeProtocolException.class);
    }

    @Test
    @DisplayName("Should encode every BrowserChannel type")
    void shouldEncodeValues() {
        // given
        Value nullValue = new Value();
        nullValue.setNull();
        Value undefined = new Value();
        undefined.setUndefined();
        Value bool = new Value();
        bool.setBoolean(false);
        Value shortValue = new Value();
        shortValue.setShort((short) 7);
        Value byteValue = new Value();
        byteValue.setByte((byte) -3);
        Value charValue = new Value();
        charValue.setChar('A');
        Value doubleValue = new Value();
        doubleValue.setDouble(1.5);
        Value infinity = new Value();
        infinity.setDouble(Double.POSITIVE_INFINITY);
        Value javaObject = new Value();
        javaObject.setJavaObject(new JavaObjectRef(4));
        Value jsObject = new Value();
        jsObject.setJsObject(new JsObjectRef(5));

        // when / then
        assertThat(ValueCodec.encode(nullValue)).isNull();
        assertThat(ValueCodec.encode(undefined)).isEqualTo(List.of("u"));
        assertThat(ValueCodec.encode(bool)).isEqualTo(false);
        assertThat(ValueCodec.encode(ValueCodec.intValue(12))).isEqualTo(12);
        assertThat(ValueCodec.encode(shortValue)).isEqualTo(7);
        assertThat(ValueCodec.encode(byteValue)).isEqualTo(-3);
        assertThat(ValueCodec.encode(charValue)).isEqualTo(65);
        assertThat(ValueCodec.encode(doubleValue)).isEqualTo(1.5);
        assertThat(ValueCodec.encode(infinity)).isEqualTo(List.of("d", "Infinity"));
        assertThat(ValueCodec.encode(ValueCodec.stringValue("s"))).isEqualTo("s");
        assertThat(ValueCodec.encode(javaObject)).isEqualTo(List.of("j", 4));
        assertThat(ValueCodec.encode(jsObject)).isEqualTo(List.of("o", 5));
    }
}
