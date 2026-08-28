package io.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldInstanceRequest} 校验契约：模板非空、instanceId 合法。
 */
class WorldInstanceRequestTest {

    private static final WorldTemplateRef TEMPLATE =
            new WorldTemplateRef("arena-desert", Path.of("/data/templates/arena.slime"));

    @Test
    void validRequestIsAccepted() {
        final WorldInstanceRequest request =
                assertDoesNotThrow(() -> new WorldInstanceRequest(TEMPLATE, "match-7f3c2a"));
        assertEquals(TEMPLATE, request.template());
        assertEquals("match-7f3c2a", request.instanceId());
    }

    @Test
    void nullTemplateIsRejected() {
        assertThrows(NullPointerException.class, () -> new WorldInstanceRequest(null, "match-1"));
    }

    @Test
    void nullInstanceIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new WorldInstanceRequest(TEMPLATE, null));
    }

    @Test
    void illegalInstanceIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldInstanceRequest(TEMPLATE, "Match-1"));
        assertThrows(IllegalArgumentException.class, () -> new WorldInstanceRequest(TEMPLATE, "match one"));
    }
}
