package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** YamlScalar 标量发射器：数字/布尔/枚举/空值明文，字符串按 YAML 规则加引号。 */
class YamlScalarTest {

    private enum Mode {
        CHAIN
    }

    @Test
    void numbersPlain() {
        assertEquals("42", YamlScalar.encode(42));
        assertEquals("128", YamlScalar.encode(128L));
        assertEquals("3.14", YamlScalar.encode(3.14));
        assertEquals("0.5", YamlScalar.encode(0.5));
    }

    @Test
    void booleansAndEnumsAndNull() {
        assertEquals("true", YamlScalar.encode(true));
        assertEquals("false", YamlScalar.encode(false));
        assertEquals("CHAIN", YamlScalar.encode(Mode.CHAIN));
        assertEquals("~", YamlScalar.encode(null));
    }

    @Test
    void plainStringsStayUnquoted() {
        assertEquals("hello", YamlScalar.encode("hello"));
        assertEquals("max-distance", YamlScalar.encode("max-distance"));
        assertEquals("默认消息", YamlScalar.encode("默认消息"));
    }

    @Test
    void stringsNeedingQuotesAreQuoted() {
        assertEquals("\"\"", YamlScalar.encode(""));
        assertEquals("' true'", YamlScalar.encode(" true"));
        assertEquals("'true'", YamlScalar.encode("true"));
        assertEquals("'123'", YamlScalar.encode("123"));
        assertEquals("'a: b'", YamlScalar.encode("a: b"));
        assertEquals("'hello # world'", YamlScalar.encode("hello # world"));
        assertEquals("'#leading'", YamlScalar.encode("#leading"));
        assertEquals("'-dash'", YamlScalar.encode("-dash"));
    }

    @Test
    void singleQuoteEscapedAsDoubleQuote() {
        assertEquals("\"it's a test\"", YamlScalar.encode("it's a test"));
        assertEquals("\"line1\\nline2\"", YamlScalar.encode("line1\nline2"));
        assertEquals("\"\\\"quoted\\\"\"", YamlScalar.encode("\"quoted\""));
        assertEquals("\"tab\\there\"", YamlScalar.encode("tab\there"));
    }

    @Test
    void lowerCaseBoolLikeWordsAreQuoted() {
        assertEquals("'null'", YamlScalar.encode("null"));
        assertEquals("'yes'", YamlScalar.encode("yes"));
        assertEquals("'on'", YamlScalar.encode("on"));
        assertEquals("'~'", YamlScalar.encode("~"));
        assertEquals("'0x1F'", YamlScalar.encode("0x1F"));
    }

    @Test
    void mapsEncodeAsFlowMap() {
        assertEquals("{}", YamlScalar.encode(java.util.Map.of()));
        java.util.Map<String, Object> tier = new java.util.LinkedHashMap<>();
        tier.put("id", "bronze");
        tier.put("threshold", 200);
        assertEquals("{id: bronze, threshold: 200}", YamlScalar.encode(tier));
        // 数字键明文；字符串键按引号规则
        java.util.Map<Object, Object> num = new java.util.LinkedHashMap<>();
        num.put(1, 10);
        num.put(2, 20);
        assertEquals("{1: 10, 2: 20}", YamlScalar.encode(num));
        // flow map 可作 flow list 元素（activity.tiers 默认发射形态）
        java.util.Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("id", "a");
        a.put("threshold", 1);
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("id", "b");
        b.put("threshold", 2);
        assertEquals("[{id: a, threshold: 1}, {id: b, threshold: 2}]", YamlScalar.encode(java.util.List.of(a, b)));
    }
}
