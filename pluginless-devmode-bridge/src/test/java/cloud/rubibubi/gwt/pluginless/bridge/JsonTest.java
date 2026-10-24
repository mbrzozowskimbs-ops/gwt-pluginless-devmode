package cloud.rubibubi.gwt.pluginless.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Json codec")
class JsonTest {

    @Test
    @DisplayName("Should parse nested arrays with all scalar types")
    void shouldParseScalarsAndArrays() {
        // when
        Object parsed = Json.parse(" [ \"a\", 1, -2.5, 1e3, true, false, null, [\"u\"], [] ] ");

        // then
        assertThat(parsed).isEqualTo(Arrays.asList("a", 1L, -2.5, 1000.0, true, false, null, List.of("u"), List.of()));
    }

    @Test
    @DisplayName("Should parse objects preserving key order")
    void shouldParseObjects() {
        // when
        Object parsed = Json.parse("{\"b\":1,\"a\":{\"c\":[1]}}");

        // then
        assertThat(parsed).isEqualTo(Map.of("b", 1L, "a", Map.of("c", List.of(1L))));
        assertThat(List.copyOf(((Map<?, ?>) parsed).keySet())).isEqualTo(List.of("b", "a"));
    }

    @Test
    @DisplayName("Should decode string escapes including unicode and surrogate pairs")
    void shouldDecodeEscapes() {
        // when
        Object parsed = Json.parse("\"q\\\"b\\\\s\\/n\\nt\\tu\\u0105\\ud83d\\ude00\"");

        // then
        assertThat(parsed).isEqualTo("q\"b\\s/n\nt\tuą\uD83D\uDE00");
    }

    @Test
    @DisplayName("Should round-trip strings with control characters, Polish letters and emoji")
    void shouldRoundTripStrings() {
        // given
        String text = "zażółć \"gęślą\" jaźń\n\t\u0001 \uD83D\uDE00 </script>";

        // when
        Object parsed = Json.parse(Json.write(List.of(text)));

        // then
        assertThat(parsed).isEqualTo(List.of(text));
    }

    @Test
    @DisplayName("Should escape lone surrogates so they survive UTF-8 encoding")
    void shouldEscapeLoneSurrogates() {
        // given
        String text = "a\uD800b\uDC00";

        // when
        String json = Json.write(text);

        // then
        assertThat(json).isEqualTo("\"a\\ud800b\\udc00\"");
        assertThat(Json.parse(json)).isEqualTo(text);
    }

    @Test
    @DisplayName("Should write numbers, booleans, null and nested lists")
    void shouldWriteValues() {
        // when
        String json = Json.write(Arrays.asList(1, 2L, 2.5, true, null, List.of("j", 3)));

        // then
        assertThat(json).isEqualTo("[1,2,2.5,true,null,[\"j\",3]]");
    }

    @Test
    @DisplayName("Should parse integers beyond long range as doubles")
    void shouldParseHugeIntegersAsDoubles() {
        // when
        Object parsed = Json.parse("123456789012345678901234567890");

        // then
        assertThat(parsed).isEqualTo(1.2345678901234568E29);
    }

    @Test
    @DisplayName("Should refuse non-finite numbers")
    void shouldRefuseNonFiniteNumbers() {
        assertThatThrownBy(() -> Json.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[", "[1,]", "[1 2]", "\"abc", "tru", "{\"a\" 1}", "[1] x", "\"\\x\"", "\"\\u12\"",
            "\"a\u0001\"", "-", "1.2.3"})
    @DisplayName("Should reject malformed JSON")
    void shouldRejectMalformedJson(String text) {
        assertThatThrownBy(() -> Json.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }
}
