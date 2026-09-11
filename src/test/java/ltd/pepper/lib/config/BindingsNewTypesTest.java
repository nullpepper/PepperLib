package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ltd.pepper.lib.yaml.YamlMap;
import org.junit.jupiter.api.Test;

/** ConfigMe 对齐（0.14.0）：Optional/Set/数组/时间类型的注解一等绑定 + 默认文件发射。 */
class BindingsNewTypesTest {

    @ConfigModel
    static class Modern {
        @ConfigComment("欢迎语（缺省不显示）")
        Optional<String> motd = Optional.empty();

        @ConfigComment("可选上限")
        Optional<Integer> cap = Optional.of(5);

        @ConfigComment("标签集合")
        Set<String> tags = Set.of("a");

        @ConfigComment("生效日期")
        LocalDate date = LocalDate.of(2026, 1, 10);

        @ConfigComment("生效时间")
        LocalTime time = LocalTime.of(8, 30);

        @ConfigComment("生效时刻")
        LocalDateTime stamp = LocalDateTime.of(2026, 1, 10, 8, 30, 0);

        @ConfigComment("坐标")
        int[] coord = new int[] {0, 64, 0};

        @ConfigComment("权重")
        double[] weights = new double[] {1.0, 2.5};

        @ConfigComment("别名")
        String[] aliases = new String[] {"a", "b"};
    }

    @ConfigModel
    record Rec(
            @ConfigComment("可空标题") Optional<String> title,
            @ConfigComment("标签") Set<String> tagSet) {}

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void defaultsTextEmitsNewTypesAndRoundTrips() {
        String text = Bindings.defaultsText(Modern.class);
        Map<String, Object> p = YamlMap.parse(text);
        // Optional 空 → 空标量（null）；有值 → 内值
        assertEquals(null, p.get("motd"));
        assertEquals(5, p.get("cap"));
        // Set → flow list（保序）
        assertEquals(List.of("a"), p.get("tags"));
        // 时间 → ISO 文本（引号守卫，回读为 String 而非 Date/sexagesimal）
        assertEquals("2026-01-10", p.get("date"));
        assertEquals("08:30", p.get("time"));
        assertEquals("2026-01-10T08:30", p.get("stamp"));
        // 数组 → flow list
        assertEquals(List.of(0, 64, 0), p.get("coord"));
        assertEquals(List.of(1.0, 2.5), p.get("weights"));
        assertEquals(List.of("a", "b"), p.get("aliases"));
        // 引号守卫落地（否则 snakeyaml 会把日期当 Date、sexagesimal 当 Integer）
        assertTrue(text.contains("date: '2026-01-10'"), "日期应加引号\n" + text);
        assertTrue(text.contains("time: '08:30'"), "sexagesimal 形态时间应加引号\n" + text);
    }

    @Test
    void loadMissingFallsBackToFieldDefaultsForNewTypes() {
        Modern m = Bindings.load(Modern.class, map(), new IssueCollector());
        assertEquals(Optional.empty(), m.motd);
        assertEquals(Optional.of(5), m.cap);
        assertEquals(Set.of("a"), m.tags);
        assertEquals(LocalDate.of(2026, 1, 10), m.date);
        assertEquals(LocalTime.of(8, 30), m.time);
        assertEquals(LocalDateTime.of(2026, 1, 10, 8, 30, 0), m.stamp);
        assertArrayEquals(new int[] {0, 64, 0}, m.coord);
        assertArrayEquals(new double[] {1.0, 2.5}, m.weights, 1e-9);
        assertArrayEquals(new String[] {"a", "b"}, m.aliases);
    }

    @Test
    void loadPresentCoercesNewTypes() {
        IssueCollector issues = new IssueCollector();
        Modern m = Bindings.load(
                Modern.class,
                map(
                        "motd",
                        "hello",
                        "cap",
                        9,
                        "tags",
                        List.of("b", "a", "b"),
                        "date",
                        "2020-12-31",
                        "time",
                        "14:30",
                        "stamp",
                        "2021-01-02 03:04:05",
                        "coord",
                        List.of(1, 2, 3),
                        "weights",
                        List.of(0.5, 1.5),
                        "aliases",
                        List.of("x", "y")),
                issues);
        assertEquals(Optional.of("hello"), m.motd);
        assertEquals(Optional.of(9), m.cap);
        // Set 保序去重（对齐 ConfigMe SetProperty/SetPropertyType）
        assertEquals(List.of("b", "a"), new ArrayList<>(m.tags));
        assertEquals(LocalDate.of(2020, 12, 31), m.date);
        assertEquals(LocalTime.of(14, 30), m.time);
        assertEquals(LocalDateTime.of(2021, 1, 2, 3, 4, 5), m.stamp);
        assertArrayEquals(new int[] {1, 2, 3}, m.coord);
        assertArrayEquals(new double[] {0.5, 1.5}, m.weights, 1e-9);
        assertArrayEquals(new String[] {"x", "y"}, m.aliases);
        assertEquals(0, issues.issues().size());
    }

    @Test
    void loadParsesAlternateTemporalFormats() {
        Modern m = Bindings.load(
                Modern.class,
                map("date", "25.12.2025", "time", "22.15", "stamp", "25.12.2025 10:20:30"),
                new IssueCollector());
        assertEquals(LocalDate.of(2025, 12, 25), m.date);
        assertEquals(LocalTime.of(22, 15), m.time);
        assertEquals(LocalDateTime.of(2025, 12, 25, 10, 20, 30), m.stamp);
    }

    @Test
    void loadAcceptsSnakeyamlDateForUnquotedTemporal() {
        // 管理员手写未加引号日期：snakeyaml 2.6 解析为 java.util.Date → 宽容转本地日历
        Map<String, Object> raw = YamlMap.parse("date: 2026-01-10\n");
        Modern m = Bindings.load(Modern.class, raw, new IssueCollector());
        assertEquals(LocalDate.of(2026, 1, 10), m.date);
    }

    @Test
    void loadInvalidNewTypesFallBackToDefaults() {
        IssueCollector issues = new IssueCollector();
        Modern m = Bindings.load(Modern.class, map("date", "not-a-date", "tags", "not-a-list"), issues);
        assertEquals(LocalDate.of(2026, 1, 10), m.date);
        assertEquals(Set.of("a"), m.tags);
        assertTrue(issues.issues().stream().anyMatch(i -> i.path().equals("date")), "时间解析失败应有 WARN");
    }

    @Test
    void recordComponentsSupportNewTypes() {
        String text = Bindings.defaultsText(Rec.class);
        Map<String, Object> p = YamlMap.parse(text);
        assertEquals(null, p.get("title"));
        assertEquals(List.of(), p.get("tag-set"));
        Rec r = Bindings.load(Rec.class, map("title", "t", "tag-set", List.of("x", "y")), new IssueCollector());
        assertEquals(Optional.of("t"), r.title());
        assertEquals(Set.of("x", "y"), r.tagSet());
    }

    @Test
    void yamlScalarEncodesSetArrayAndOptional() {
        assertEquals("[]", YamlScalar.encode(Set.of()));
        assertEquals("[a, b]", YamlScalar.encode(new java.util.LinkedHashSet<>(List.of("a", "b"))));
        assertEquals("[1, 2]", YamlScalar.encode(new int[] {1, 2}));
        assertEquals("", YamlScalar.encode(Optional.empty()));
        assertEquals("x", YamlScalar.encode(Optional.of("x")));
        assertEquals("'08:30'", YamlScalar.encode("08:30"));
        assertEquals("'2026-01-10'", YamlScalar.encode("2026-01-10"));
        assertEquals("'12:34:56'", YamlScalar.encode("12:34:56"));
        assertEquals("'2021-01-02 03:04:05'", YamlScalar.encode("2021-01-02 03:04:05"));
    }
}
