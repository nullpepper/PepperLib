package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.pepper.lib.yaml.YamlMap;
import org.junit.jupiter.api.Test;

/**
 * record 形态模型的嵌套能力（0.15.0）：嵌套 record 作配置节、{@code List<record>}/{@code Map&lt;String,record&gt;}
 * 元素构造真 record，以及 {@link ConfigDefaults} 默认实例来源（取代类型零值）。
 */
class BindingsRecordModelsTest {

    /** 嵌套节 record：路径显式 kebab。 */
    @ConfigModel
    record Profile(
            @ConfigPath("spawn-radius") int spawnRadius,
            @ConfigPath("name") String name,
            boolean pvp) {}

    /** 集合元素 record：无静态默认工厂 → 缺键回落类型零值。 */
    @ConfigModel
    record Rule(
            @ConfigPath("slot") int slot,
            @ConfigPath("fee-cents") long feeCents,
            String tier) {}

    /** 集合元素 record：带 {@link ConfigDefaults} 工厂 → 缺键回落工厂默认。 */
    @ConfigModel
    record Priced(
            @ConfigPath("slot") int slot,
            @ConfigPath("fee-cents") long feeCents) {

        @ConfigDefaults
        static Priced standard() {
            return new Priced(1, 500L);
        }
    }

    /** 顶层 record：嵌套节 + 两种集合 + 标量。 */
    @ConfigModel
    record Group(
            @ConfigPath("profile") Profile profile,
            @ConfigPath("worlds") Map<String, Profile> worlds,
            @ConfigPath("rules") List<Rule> rules,
            @ConfigPath("flat") int flat) {}

    /** 顶层 record 带 {@link ConfigDefaults}：全树默认来自工厂。 */
    @ConfigModel
    record Sourced(
            @ConfigPath("profile") Profile profile,
            @ConfigPath("flat") int flat) {

        @ConfigDefaults
        static Sourced shipped() {
            return new Sourced(new Profile(32, "shipped", true), 7);
        }
    }

    /** TreeCut 形态：可变 class 模型里嵌 record 集合（class 路径）。 */
    @ConfigModel
    static class Pojo {
        @ConfigPath("rules")
        List<Rule> rules = List.of();

        @ConfigPath("profile")
        Profile profile = new Profile(1, "pojo", false);
    }

    /** 三层 record 嵌套（PepperClaim {@code contexts.boundary-display.show-all.*} 形态）。 */
    @ConfigModel
    record Deep(Profile profile, @ConfigPath("level") int level) {}

    @ConfigModel
    record Deeper(Deep deep, @ConfigPath("top") int top) {}

    /** class 节里再嵌 record 节：节引用需按属主字段链定位（外层实例 → inner）。 */
    @ConfigModel
    static class Outer {
        @ConfigModel
        static class Inner {
            @ConfigPath("profile")
            Profile profile = new Profile(3, "inner", true);
        }

        @ConfigPath("inner")
        Inner inner = new Inner();

        @ConfigPath("flat")
        int flat = 5;
    }

    /** 未标注 @ConfigModel 的 record：不得静默产出裸 Map。 */
    record Plain(int n) {}

    @ConfigModel
    record BadElement(@ConfigPath("items") List<Plain> items) {}

    /** 金额 codec：YAML 写"元"，模型存"分"（对齐 PepperClaim {@code moneyCents}）。 */
    static final class CentsCodec implements ConfigCodec<Long> {
        @Override
        public Object toConfig(Long value) {
            return value == null ? 0.0 : value / 100.0;
        }

        @Override
        public Long fromConfig(Object raw) {
            return raw instanceof Number n ? Math.round(n.doubleValue() * 100) : 0L;
        }
    }

    /** record 组件的 @Codec（此前 @Codec 只标 FIELD，record 组件挂不上）。 */
    @ConfigModel
    record Money(
            @Codec(CentsCodec.class) @ConfigPath("base-price")
            long basePriceCents,

            @ConfigPath("note") String note) {

        @ConfigDefaults
        static Money standard() {
            return new Money(1000L, "std");
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void nestedRecordSectionBindsFromDottedKeys() {
        Map<String, Object> raw = map("profile", map("spawn-radius", 12, "name", "harbor", "pvp", true), "flat", 3);
        Group g = Bindings.load(Group.class, raw, new IssueCollector());
        assertInstanceOf(Profile.class, g.profile());
        assertEquals(12, g.profile().spawnRadius());
        assertEquals("harbor", g.profile().name());
        assertTrue(g.profile().pvp());
        assertEquals(3, g.flat());
    }

    @Test
    void missingNestedSectionFallsBackToTypeZeroWithoutThrowing() {
        Group g = Bindings.load(Group.class, map("flat", 3), new IssueCollector());
        assertInstanceOf(Profile.class, g.profile());
        assertEquals(0, g.profile().spawnRadius());
        assertEquals("", g.profile().name());
    }

    @Test
    void configDefaultsFactorySuppliesWholeTreeDefaults() {
        Sourced s = Bindings.load(Sourced.class, Map.of(), new IssueCollector());
        assertEquals(7, s.flat());
        assertInstanceOf(Profile.class, s.profile());
        assertEquals(32, s.profile().spawnRadius());
        assertEquals("shipped", s.profile().name());
    }

    @Test
    void listOfRecordsBindsRealRecordsNotRawMaps() {
        List<Object> rawRules = List.of(
                map("slot", 2, "fee-cents", 900L, "tier", "silver"),
                map("slot", 3, "fee-cents", 1500L, "tier", "gold"));
        Group g = Bindings.load(Group.class, map("rules", rawRules), new IssueCollector());
        assertEquals(2, g.rules().size());
        assertInstanceOf(Rule.class, g.rules().get(0));
        assertEquals(2, g.rules().get(0).slot());
        assertEquals(900L, g.rules().get(0).feeCents());
        assertEquals("gold", g.rules().get(1).tier());
    }

    @Test
    void listElementMissingKeysFallBackToElementDefaults() {
        Group typed = Bindings.load(Group.class, map("rules", List.of(map("slot", 4))), new IssueCollector());
        assertInstanceOf(Rule.class, typed.rules().get(0));
        assertEquals(0L, typed.rules().get(0).feeCents());

        @ConfigModel
        record PricedGroup(@ConfigPath("priced") List<Priced> priced) {}
        PricedGroup sourced =
                Bindings.load(PricedGroup.class, map("priced", List.of(map("slot", 9))), new IssueCollector());
        assertInstanceOf(Priced.class, sourced.priced().get(0));
        assertEquals(500L, sourced.priced().get(0).feeCents());
    }

    @Test
    void mapOfRecordsBindsRealRecordsNotRawMaps() {
        Map<String, Object> raw = map(
                "worlds",
                map(
                        "world",
                        map("spawn-radius", 5, "name", "w", "pvp", false),
                        "nether",
                        map("spawn-radius", 1, "name", "n", "pvp", true)));
        Group g = Bindings.load(Group.class, raw, new IssueCollector());
        assertEquals(2, g.worlds().size());
        assertInstanceOf(Profile.class, g.worlds().get("world"));
        assertEquals(5, g.worlds().get("world").spawnRadius());
        assertEquals("n", g.worlds().get("nether").name());
    }

    @Test
    void classModelBindsRecordSectionsAndCollections() {
        Pojo p = Bindings.load(
                Pojo.class,
                map("rules", List.of(map("slot", 1, "fee-cents", 10L, "tier", "bronze"))),
                new IssueCollector());
        assertInstanceOf(Rule.class, p.rules.get(0));
        assertEquals("bronze", p.rules.get(0).tier());
        assertInstanceOf(Profile.class, p.profile);
        assertEquals("pojo", p.profile.name());
    }

    @Test
    void unannotatedRecordElementFailsFastWithTypeName() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> Bindings.load(BadElement.class, map("items", List.of(map("n", 1))), new IssueCollector()));
        assertTrue(thrown.getMessage().contains("Plain"), "消息须点名缺注解的类型，实际：" + thrown.getMessage());
    }

    @Test
    void guardTemplateKnowsNestedRecordKeys() {
        // 自产模板必须通过守卫（嵌套 record 节整棵子树被识别为已声明键）。
        List<ConfigIssue> clean = Bindings.guardTemplate(Group.class, Bindings.defaultsText(Group.class));
        assertTrue(clean.isEmpty(), "自产模板应无 issue，实际：" + clean);

        List<ConfigIssue> dirty = Bindings.guardTemplate(Group.class, "profile.typo: 1\n");
        assertTrue(dirty.stream().anyMatch(i -> i.path().contains("typo")), "未知嵌套键应报 issue：" + dirty);
    }

    @Test
    void defaultsTextEmitsNestedRecordSections() {
        Map<String, Object> parsed = YamlMap.parse(Bindings.defaultsText(Group.class));
        assertInstanceOf(Map.class, parsed.get("profile"));
        assertEquals(0, parsed.get("flat"));
    }

    @Test
    void threeLevelRecordNestingBindsEveryLevel() {
        Map<String, Object> raw =
                map("deep", map("profile", map("spawn-radius", 7, "name", "deep", "pvp", true), "level", 2), "top", 1);
        Deeper d = Bindings.load(Deeper.class, raw, new IssueCollector());
        assertEquals(1, d.top());
        assertEquals(2, d.deep().level());
        assertInstanceOf(Profile.class, d.deep().profile());
        assertEquals(7, d.deep().profile().spawnRadius(), "第三层节的磁盘值必须被读到（曾整节退化为类型零值）");
        assertEquals("deep", d.deep().profile().name());

        Deeper empty = Bindings.load(Deeper.class, Map.of(), new IssueCollector());
        assertEquals(0, empty.deep().profile().spawnRadius(), "缺键仍回落默认");
    }

    @Test
    void classSectionHoldingRecordSectionWritesThroughOwnerChain() {
        Outer o = Bindings.load(
                Outer.class,
                map("inner", map("profile", map("spawn-radius", 44, "name", "deep-owner", "pvp", true)), "flat", 9),
                new IssueCollector());
        assertEquals(9, o.flat);
        assertInstanceOf(Profile.class, o.inner.profile, "嵌套 class 节里的 record 节须写回该节实例的字段");
        assertEquals(44, o.inner.profile.spawnRadius());
        assertEquals("deep-owner", o.inner.profile.name());
    }

    @Test
    void recordComponentCodecConvertsValueAndSuppliesDefault() {
        Money m = Bindings.load(Money.class, map("base-price", 10.0), new IssueCollector());
        assertEquals(1000L, m.basePriceCents(), "codec 应把 YAML 的 10.0 元转成 1000 分");
        assertEquals("std", m.note());

        Money missing = Bindings.load(Money.class, Map.of(), new IssueCollector());
        assertEquals(1000L, missing.basePriceCents(), "缺键回落 @ConfigDefaults 工厂的默认实例");

        String text = Bindings.defaultsText(Money.class);
        assertTrue(text.contains("base-price: 10.0"), "发射经 codec.toConfig 回写为元，实际：" + text);
    }
}
