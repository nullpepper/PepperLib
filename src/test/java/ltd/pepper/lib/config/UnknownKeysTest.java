package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 设计文档 §9 UnknownKeys：单层未知键检测（treecut ConfigValidator checkKeys 泛化）。 */
class UnknownKeysTest {

    @Test
    void returnsOnlyKeysOutsideKnownSet() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fellMode", "CHAIN");
        m.put("profileDefaults", new LinkedHashMap<>());
        m.put("unknownTop", 1);
        m.put("alsoUnknown", 2);
        assertEquals(
                List.of("unknownTop", "alsoUnknown"),
                UnknownKeys.unknown(m, Set.of("fellMode", "profileDefaults", "worlds")));
    }

    @Test
    void emptyKnownSetFlagsEverything() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        assertEquals(List.of("a", "b"), UnknownKeys.unknown(m, Set.of()));
    }

    @Test
    void allKnownReturnsEmpty() {
        Map<String, Object> m = Map.of("a", 1);
        assertEquals(List.of(), UnknownKeys.unknown(m, Set.of("a")));
    }
}
