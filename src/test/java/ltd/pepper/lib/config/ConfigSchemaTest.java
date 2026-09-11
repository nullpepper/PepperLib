package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 设计文档 §9.5：声明式 schema——knownKeys/默认值表/越界与枚举校验 + 模板一致性守卫。 */
class ConfigSchemaTest {

    private enum Mode {
        CHAIN,
        GRAVITY
    }

    private static ConfigSchema sample() {
        return ConfigSchema.builder()
                .bool("requireAxe", true)
                .intField("maxLogs", 256, v -> v >= 1)
                .longField("cooldownMs", 120L)
                .doubleField("minLeafRatio", 0.5)
                .string("language", "zh_cn")
                .enumField("fellMode", Mode.class, Mode.CHAIN)
                .list("axes")
                .build();
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void knownKeysReflectDeclaration() {
        assertEquals(
                Set.of("requireAxe", "maxLogs", "cooldownMs", "minLeafRatio", "language", "fellMode", "axes"),
                sample().knownKeys());
    }

    @Test
    void defaultsTableMatchesDeclaration() {
        Map<String, Object> d = sample().defaults();
        assertEquals(true, d.get("requireAxe"));
        assertEquals(256, d.get("maxLogs"));
        assertEquals(120L, d.get("cooldownMs"));
        assertEquals(0.5, d.get("minLeafRatio"));
        assertEquals("zh_cn", d.get("language"));
        assertEquals(Mode.CHAIN, d.get("fellMode"));
        assertEquals(List.of(), d.get("axes"));
    }

    @Test
    void typedReadWithDefaultsFallback() {
        SchemaValues sv = sample().read(Map.of(), new IssueCollector());
        assertEquals(true, sv.bool("requireAxe"));
        assertEquals(256, sv.intValue("maxLogs"));
        assertEquals(120L, sv.longValue("cooldownMs"));
        assertEquals(0.5, sv.doubleValue("minLeafRatio"));
        assertEquals("zh_cn", sv.string("language"));
        assertEquals(Mode.CHAIN, sv.enumValue("fellMode", Mode.class));
        assertEquals(List.of(), sv.list("axes"));
    }

    @Test
    void typedReadCoercesPresentValues() {
        SchemaValues sv = sample().read(
                        map(
                                "requireAxe",
                                false,
                                "maxLogs",
                                512,
                                "cooldownMs",
                                5000L,
                                "minLeafRatio",
                                0.9,
                                "language",
                                "en_us",
                                "fellMode",
                                "GRAVITY",
                                "axes",
                                List.of("a", "b")),
                        new IssueCollector());
        assertEquals(false, sv.bool("requireAxe"));
        assertEquals(512, sv.intValue("maxLogs"));
        assertEquals(5000L, sv.longValue("cooldownMs"));
        assertEquals(0.9, sv.doubleValue("minLeafRatio"));
        assertEquals("en_us", sv.string("language"));
        assertEquals(Mode.GRAVITY, sv.enumValue("fellMode", Mode.class));
        assertEquals(List.of("a", "b"), sv.list("axes"));
    }

    @Test
    void typeMismatchFallsBackSilentlyWithoutIssue() {
        // 与 Values 语义一致：类型不匹配静默回落默认，不记 issue
        IssueCollector issues = new IssueCollector();
        SchemaValues sv = sample().read(map("maxLogs", "abc", "requireAxe", ""), issues);
        assertEquals(256, sv.intValue("maxLogs"));
        assertEquals(true, sv.bool("requireAxe"));
        assertEquals(0, issues.issues().size());
    }

    @Test
    void intOutOfRangeWarnsAndFallsBackToDefault() {
        IssueCollector issues = new IssueCollector();
        SchemaValues sv = sample().read(map("maxLogs", 0), issues);
        assertEquals(256, sv.intValue("maxLogs"), "越界回落默认");
        assertEquals(1, issues.count(IssueLevel.WARN));
        assertEquals("maxLogs", issues.issues().get(0).path());
        assertTrue(issues.issues().get(0).message().contains("0"));
    }

    @Test
    void enumUnknownWarnsAndFallsBack() {
        IssueCollector issues = new IssueCollector();
        SchemaValues sv = sample().read(map("fellMode", "NOPE"), issues);
        assertEquals(Mode.CHAIN, sv.enumValue("fellMode", Mode.class));
        assertEquals(1, issues.count(IssueLevel.WARN));
    }

    @Test
    void duplicateKeyDeclarationRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigSchema.builder().bool("a", true).bool("a", false).build());
    }

    @Test
    void templateGuardFlagsUnknownKeys() {
        ConfigSchema schema = sample();
        // 模板含未知键（typoKey）；全部 declared 键都在
        String tpl = "requireAxe: true\nmaxLogs: 100\ncooldownMs: 1000\nminLeafRatio: 0.5\n"
                + "language: zh_cn\nfellMode: CHAIN\naxes: []\ntypoKey: 1\n";
        List<ConfigIssue> issues = SchemaTemplateGuard.check(schema, tpl);
        assertEquals(1, issues.size(), "仅模板未知键一条");
        assertEquals("typoKey", issues.get(0).path());
    }

    @Test
    void templateGuardFlagsMissingDeclaredKey() {
        ConfigSchema schema = sample();
        // 模板缺 declared 键（axes）
        String tpl = "requireAxe: true\nmaxLogs: 100\ncooldownMs: 1000\nminLeafRatio: 0.5\n"
                + "language: zh_cn\nfellMode: CHAIN\n";
        List<ConfigIssue> issues = SchemaTemplateGuard.check(schema, tpl);
        assertEquals(1, issues.size(), "缺 declared 键一条");
        assertTrue(issues.get(0).message().contains("不在模板"));
    }

    @Test
    void templateGuardAcceptsMatchingTemplate() {
        ConfigSchema schema = sample();
        String tpl = "requireAxe: true\nmaxLogs: 100\ncooldownMs: 1000\nminLeafRatio: 0.5\n"
                + "language: zh_cn\nfellMode: CHAIN\naxes: []\n";
        assertTrue(SchemaTemplateGuard.check(schema, tpl).isEmpty());
    }

    @Test
    void templateGuardReportsParseErrors() {
        List<ConfigIssue> issues = SchemaTemplateGuard.check(sample(), "a: 1\na: 2\n");
        assertEquals(1, issues.size());
        assertEquals(IssueLevel.ERROR, issues.get(0).level());
        assertFalse(issues.get(0).message().isBlank());
    }
}
