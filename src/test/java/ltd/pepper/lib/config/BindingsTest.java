package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.pepper.lib.yaml.YamlMap;
import org.junit.jupiter.api.Test;

/** Bindings 注解驱动绑定：schema 生成、默认文件发射（含 @ConfigComment）、类型化装载与校验。 */
class BindingsTest {

    private enum Mode {
        CHAIN,
        GRAVITY
    }

    @ConfigModel
    static class Sample {
        @ConfigComment({"最大距离（格）", "超过后不再判定或应用发光"})
        @ConfigPath("max-distance")
        int maxDistance = 128;

        @ConfigComment("是否允许自动使用")
        boolean autoUse = true;

        @ConfigComment("砍树模式")
        Mode mode = Mode.CHAIN;

        @ConfigRange(min = 1, max = 100)
        int level = 50;

        @ConfigComment("符合条件工具列表")
        List<String> axes = List.of();

        @ConfigModel
        static class Stats {
            @ConfigComment("冷却（毫秒）")
            long cooldownMs = 1000L;

            @ConfigComment("每级刻度")
            double tick = 0.5;
        }

        Stats stats = new Stats();
    }

    @ConfigModel
    record Rec(
            @ConfigComment("记录名") String name,
            @ConfigRange(min = 0, max = 1000) int limit) {}

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void defaultsTextEmitKebabKeysWithAnnotationComments() {
        String text = Bindings.defaultsText(Sample.class);
        Map<String, Object> parsed = YamlMap.parse(text);
        assertEquals(128, parsed.get("max-distance"));
        assertEquals(true, parsed.get("auto-use"));
        assertEquals("CHAIN", parsed.get("mode"));
        assertEquals(50, parsed.get("level"));
        assertEquals(List.of(), parsed.get("axes"));
        Map<?, ?> stats = (Map<?, ?>) parsed.get("stats");
        assertEquals(1000, stats.get("cooldown-ms"));
        assertEquals(0.5, stats.get("tick"));
        // 注释出现在正确条目上方
        int comment = text.indexOf("# 最大距离（格）");
        int key = text.indexOf("max-distance:");
        assertTrue(comment >= 0 && comment < key, "注释应在对应键上方");
    }

    @Test
    void loadMissingKeysFallsBackToDefaults() {
        Sample s = Bindings.load(Sample.class, map(), new IssueCollector());
        assertEquals(128, s.maxDistance);
        assertEquals(true, s.autoUse);
        assertEquals(Mode.CHAIN, s.mode);
        assertEquals(50, s.level);
        assertEquals(List.of(), s.axes);
        assertEquals(1000L, s.stats.cooldownMs);
        assertEquals(0.5, s.stats.tick);
    }

    @Test
    void loadCoercesPresentValues() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(
                Sample.class,
                map(
                        "max-distance",
                        256,
                        "auto-use",
                        false,
                        "mode",
                        "GRAVITY",
                        "level",
                        80,
                        "axes",
                        List.of("a", "b"),
                        "stats",
                        map("cooldown-ms", 500, "tick", 1.5)),
                issues);
        assertEquals(256, s.maxDistance);
        assertEquals(false, s.autoUse);
        assertEquals(Mode.GRAVITY, s.mode);
        assertEquals(80, s.level);
        assertEquals(List.of("a", "b"), s.axes);
        assertEquals(500L, s.stats.cooldownMs);
        assertEquals(1.5, s.stats.tick);
        assertEquals(0, issues.issues().size());
    }

    @Test
    void rangeViolationWarnsAndFallsBackToDefault() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(Sample.class, map("level", 1000), issues);
        assertEquals(50, s.level);
        assertEquals(1, issues.count(IssueLevel.WARN));
        assertEquals("level", issues.issues().get(0).path());
    }

    @Test
    void enumUnknownWarnsAndFallsBack() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(Sample.class, map("mode", "NOPE"), issues);
        assertEquals(Mode.CHAIN, s.mode);
        assertEquals(1, issues.count(IssueLevel.WARN));
    }

    @Test
    void enumMatchesCaseInsensitively() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(Sample.class, map("mode", "graVITY"), issues);
        assertEquals(Mode.GRAVITY, s.mode, "枚举匹配大小写不敏感（与旧 parseEnum 一致）");
        assertEquals(0, issues.count(IssueLevel.WARN));
    }

    @Test
    void nonFiniteNumbersFallBackToDefaultWithWarn() {
        IssueCollector issues = new IssueCollector();
        Sample s =
                Bindings.load(Sample.class, map("level", Double.NaN, "max-distance", Double.POSITIVE_INFINITY), issues);
        assertEquals(50, s.level, ".nan 不进入运行值（回落默认）");
        assertEquals(128, s.maxDistance, ".inf 不进入运行值（回落默认）");
        assertEquals(2, issues.count(IssueLevel.WARN));
    }

    @Test
    void guardTemplateFlagsUnknownKeyAndMissing() {
        List<ConfigIssue> issues = Bindings.guardTemplate(Sample.class, "max-distance: 128\ntypo: 1\n");
        assertTrue(issues.stream().anyMatch(i -> i.path().equals("typo")));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("不在模板")));
    }

    @ConfigModel
    @ConfigHeader({"Pepper 测试配置", "第二行说明"})
    static class WithHeader {
        int value = 1;
    }

    @Test
    void defaultsTextEmitsHeaderComment() {
        String text = Bindings.defaultsText(WithHeader.class);
        assertTrue(text.startsWith("# Pepper 测试配置\n# 第二行说明\nvalue: 1\n"), text);
    }

    @Test
    void recordModelLoadsAndEmits() {
        IssueCollector issues = new IssueCollector();
        Rec r = Bindings.load(Rec.class, map("name", "hello", "limit", 9), issues);
        assertEquals("hello", r.name());
        assertEquals(9, r.limit());

        String text = Bindings.defaultsText(Rec.class);
        Map<String, Object> parsed = YamlMap.parse(text);
        assertEquals("", parsed.get("name"));
        assertEquals(0, parsed.get("limit"));
        // 记录组件默认值 = 类型零值；@ConfigComment 进默认文件
        assertTrue(text.contains("# 记录名"));
    }

    // ------------------------------------------------------------------
    // Map 一等绑定（等级曲线 / 容量覆盖表等）
    // ------------------------------------------------------------------

    @ConfigModel
    static class WithMaps {
        @ConfigComment("等级 -> 最大成员数")
        LinkedHashMap<Integer, Integer> maxMembersByLevel = orderedIntMap();

        @ConfigComment("来源 -> 每日上限")
        Map<String, Integer> sourceCaps = orderedSourceCaps();

        Map<Integer, Long> expByLevel = new LinkedHashMap<>();

        private static LinkedHashMap<Integer, Integer> orderedIntMap() {
            LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
            m.put(5, 20);
            m.put(10, 50);
            return m;
        }

        private static LinkedHashMap<String, Integer> orderedSourceCaps() {
            LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
            m.put("chat", 100);
            m.put("transfer", 50);
            return m;
        }
    }

    private static Map<Integer, Integer> intMap(Integer... kv) {
        LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void mapFieldsEmitNestedMapDefaults() {
        String text = Bindings.defaultsText(WithMaps.class);
        Map<String, Object> parsed = YamlMap.parse(text);
        Map<?, ?> members = (Map<?, ?>) parsed.get("max-members-by-level");
        assertEquals(20, members.get(5));
        assertEquals(50, members.get(10));
        Map<?, ?> caps = (Map<?, ?>) parsed.get("source-caps");
        assertEquals(100, caps.get("chat"));
        assertEquals(50, caps.get("transfer"));
        assertEquals(Map.of(), parsed.get("exp-by-level"));
        // 嵌套 Map 条目按声明序逐行写出（可读格式）
        int idxChat = text.indexOf("chat: 100");
        int idxTransfer = text.indexOf("transfer: 50");
        assertTrue(idxChat >= 0 && idxTransfer > idxChat, "映射条目按声明序逐行: " + text);
    }

    @Test
    void mapFieldsBindNumericAndStringKeys() {
        IssueCollector issues = new IssueCollector();
        Map<String, Object> raw = map(
                "max-members-by-level",
                intMap(5, 25, 10, 60),
                "source-caps",
                map("chat", 200, "shop", 30),
                "exp-by-level",
                map("5", 1000L, "10", 5000L));
        WithMaps m = Bindings.load(WithMaps.class, raw, issues);
        assertEquals(Integer.valueOf(25), m.maxMembersByLevel.get(5));
        assertEquals(Integer.valueOf(60), m.maxMembersByLevel.get(10));
        assertEquals(Integer.valueOf(200), m.sourceCaps.get("chat"));
        assertEquals(Integer.valueOf(30), m.sourceCaps.get("shop"));
        assertEquals(Long.valueOf(1000L), m.expByLevel.get(5));
        assertEquals(Long.valueOf(5000L), m.expByLevel.get(10));
        assertEquals(0, issues.issues().size());
    }

    @Test
    void mapFieldsFallBackToDeclaredDefaultOnMissingOrWrongType() {
        IssueCollector issues = new IssueCollector();
        WithMaps m = Bindings.load(WithMaps.class, map("max-members-by-level", "nope"), issues);
        assertEquals(Integer.valueOf(20), m.maxMembersByLevel.get(5), "类型不匹配回落声明默认");
        assertEquals(Integer.valueOf(100), m.sourceCaps.get("chat"), "未提供键保持声明默认");
        assertEquals(Map.of(), m.expByLevel, "声明默认空 Map 保持空");
        assertEquals(0, issues.issues().size());
    }

    // ------------------------------------------------------------------
    // @ConfigRange clamp 模式（越界夹紧保留数值，默认回落默认值）
    // ------------------------------------------------------------------

    @ConfigModel
    static class ClampModel {
        @ConfigRange(min = 1, max = 100, clamp = true)
        int displayOffset = 6;

        @ConfigRange(min = 0, max = 1, clamp = true)
        double chance = 0.12;

        @ConfigRange(min = 1, max = 16, clamp = false)
        int fallbackMode = 4;
    }

    @Test
    void rangeClampClampsToBoundsAndWarns() {
        IssueCollector issues = new IssueCollector();
        ClampModel m = Bindings.load(ClampModel.class, map("display-offset", 1000, "chance", 5.0), issues);
        assertEquals(100, m.displayOffset, "越界夹紧到上界而非回落默认 6");
        assertEquals(1.0, m.chance, 0.0, "double 越界夹紧");
        assertEquals(2, issues.count(IssueLevel.WARN));
    }

    @Test
    void rangeClampFallsBackToDefaultWhenDisabled() {
        IssueCollector issues = new IssueCollector();
        ClampModel m = Bindings.load(ClampModel.class, map("fallback-mode", 99), issues);
        assertEquals(4, m.fallbackMode, "clamp=false 保持回落到默认值");
        assertEquals(1, issues.count(IssueLevel.WARN));
    }

    // ------------------------------------------------------------------
    // ConfigPostLoad 装载后处理（派生字段解析 / 材质化化）
    // ------------------------------------------------------------------

    @ConfigModel
    static class PostLoadModel {
        @ConfigComment("方块 id 列表（含 #tag）")
        List<String> blocks = List.of("minecraft:glass", "#minecraft:planks");

        /** 装载后由 post-load 填充（transient 不进 schema / 不被注解绑定）。 */
        transient List<String> resolved = List.of();
    }

    @Test
    void postLoadHookRunsAfterBindingAndCanAddIssues() {
        IssueCollector issues = new IssueCollector();
        PostLoadModel m =
                Bindings.load(PostLoadModel.class, map("blocks", List.of("minecraft:air")), issues, (model, iss) -> {
                    model.resolved = new java.util.ArrayList<>(model.blocks);
                    model.resolved.add("+derived");
                    iss.add(new ConfigIssue(IssueLevel.WARN, "blocks", "自定义后处理告警", "检查配置"));
                });
        assertEquals(List.of("minecraft:air", "+derived"), m.resolved, "post-load 改写模型");
        assertEquals(1, issues.count(IssueLevel.WARN));
    }

    @Test
    void postLoadHookIsOptional() {
        PostLoadModel m = Bindings.load(PostLoadModel.class, map(), new IssueCollector());
        assertEquals(List.of(), m.resolved, "无 hook 时派生字段维持声明默认");
    }

    // ------------------------------------------------------------------
    // 发射：多兄弟节（同深度异键）不复用旧节链
    // ------------------------------------------------------------------

    @ConfigModel
    static class MultiSection {
        @ConfigModel
        static class Gate {
            boolean requireAxe = true;

            int minLogs = 4;
        }

        Gate gate = new Gate();

        @ConfigModel
        static class Effects {
            boolean enabled = true;

            int maxAnimatedBlocks = 1024;
        }

        Effects effects = new Effects();

        @ConfigModel
        static class Debug {
            boolean enabled = false;
        }

        Debug debug = new Debug();

        @ConfigModel
        static class Info {
            @ConfigModel
            static class Highlight {
                boolean enabled = true;

                @ConfigPath("max-blocks")
                int maxBlocks = 512;
            }

            Highlight highlight = new Highlight();
        }

        Info info = new Info();
    }

    @Test
    void emitSeparatesSiblingSectionsAtSameDepth() {
        String text = Bindings.defaultsText(MultiSection.class);
        Map<String, Object> parsed = YamlMap.parse(text);
        Map<?, ?> gate = (Map<?, ?>) parsed.get("gate");
        assertEquals(true, gate.get("require-axe"));
        assertEquals(4, gate.get("min-logs"));
        Map<?, ?> effects = (Map<?, ?>) parsed.get("effects");
        assertEquals(true, effects.get("enabled"));
        assertEquals(1024, effects.get("max-animated-blocks"));
        Map<?, ?> debug = (Map<?, ?>) parsed.get("debug");
        assertEquals(false, debug.get("enabled"));
        Map<?, ?> info = (Map<?, ?>) parsed.get("info");
        Map<?, ?> highlight = (Map<?, ?>) info.get("highlight");
        assertEquals(true, highlight.get("enabled"));
        assertEquals(512, highlight.get("max-blocks"));
    }
}
