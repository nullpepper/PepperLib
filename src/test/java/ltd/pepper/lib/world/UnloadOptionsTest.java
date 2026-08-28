package ltd.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link UnloadOptions} 两种语义工厂：业务卸载（清场后卸载）与关闭清理（强制卸载）。
 */
class UnloadOptionsTest {

    @Test
    void discardWhenEmptyIsBusinessTeardownSemantics() {
        final UnloadOptions options = UnloadOptions.discardWhenEmpty();
        assertFalse(options.save(), "实例永不保存");
        assertTrue(options.requireEmpty(), "业务卸载要求世界为空");
    }

    @Test
    void discardForShutdownIsForceSemantics() {
        final UnloadOptions options = UnloadOptions.discardForShutdown();
        assertFalse(options.save(), "关闭清理同样不保存");
        assertFalse(options.requireEmpty(), "关闭清理不因玩家在场而放弃");
    }

    @Test
    void recordEqualityHolds() {
        assertEquals(new UnloadOptions(false, true), UnloadOptions.discardWhenEmpty());
        assertEquals(new UnloadOptions(false, false), UnloadOptions.discardForShutdown());
    }
}
