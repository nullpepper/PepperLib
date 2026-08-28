package io.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * {@link WorldProviderException} 契约：稳定错误码 + 消息 + 原因链。
 */
class WorldProviderExceptionTest {

    @Test
    void errorCodeIsPreserved() {
        final WorldProviderException ex =
                new WorldProviderException(WorldProviderError.WORLD_NOT_EMPTY, "world has players");
        assertEquals(WorldProviderError.WORLD_NOT_EMPTY, ex.error());
        assertEquals("world has players", ex.getMessage());
    }

    @Test
    void causeIsPreserved() {
        final IllegalStateException cause = new IllegalStateException("boom");
        final WorldProviderException ex =
                new WorldProviderException(WorldProviderError.WORLD_LOAD_FAILED, "load failed", cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void allDeclaredErrorCodesAreStable() {
        // 契约守卫：错误码集合是公共 API 面，删除/改名即破坏调用方分支。
        assertEquals(8, WorldProviderError.values().length, "WorldProviderError 枚举不得随意增删；增删须走 API 变更流程");
    }
}
