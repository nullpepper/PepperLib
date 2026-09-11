package ltd.pepper.lib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** @Codec 自定义类型序列化注册点：标量往返、默认发射、缺失/非法值回落默认。 */
class ConfigCodecTest {

    record Duration(int minutes) {
        @Override
        public String toString() {
            return this.minutes + "m";
        }
    }

    static final class MinutesCodec implements ConfigCodec<Duration> {
        @Override
        public Object toConfig(Duration value) {
            return value.minutes() + "m";
        }

        @Override
        public Duration fromConfig(Object raw) {
            String s = String.valueOf(raw);
            if (!s.endsWith("m")) {
                throw new IllegalArgumentException("bad duration: " + raw);
            }
            return new Duration(Integer.parseInt(s.substring(0, s.length() - 1)));
        }
    }

    @ConfigModel
    static class Sample {
        @ConfigComment("冷却时长")
        @Codec(MinutesCodec.class)
        Duration cooldown = new Duration(30);
    }

    @Test
    void defaultsTextEncodesViaCodec() {
        assertEquals("# 冷却时长\ncooldown: 30m\n", Bindings.defaultsText(Sample.class));
    }

    @Test
    void loadRoundTripsThroughCodec() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(Sample.class, Map.of("cooldown", "45m"), issues);
        assertEquals(new Duration(45), s.cooldown);
    }

    @Test
    void loadMissingFallsBackToFieldDefault() {
        Sample s = Bindings.load(Sample.class, Map.of(), new IssueCollector());
        assertEquals(new Duration(30), s.cooldown);
    }

    @Test
    void invalidRawFallsBackToDefaultSilently() {
        IssueCollector issues = new IssueCollector();
        Sample s = Bindings.load(Sample.class, Map.of("cooldown", "zzz-bad"), issues);
        assertEquals(new Duration(30), s.cooldown);
        assertEquals(0, issues.issues().size());
    }
}
