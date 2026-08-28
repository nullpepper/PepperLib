package io.pepper.lib.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link WorldTemplateRef} 校验契约：id 合法字符、source 必须为绝对路径、非空。
 */
class WorldTemplateRefTest {

    private static final Path ABSOLUTE = Path.of("/data/plugins/Arena/templates/arena-desert.slime");

    @Test
    void validTemplateIsAccepted() {
        final WorldTemplateRef ref = assertDoesNotThrow(() -> new WorldTemplateRef("arena-desert", ABSOLUTE));
        assertEquals("arena-desert", ref.id());
        assertEquals(ABSOLUTE, ref.source());
    }

    @Test
    void nullIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new WorldTemplateRef(null, ABSOLUTE));
    }

    @Test
    void blankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldTemplateRef("  ", ABSOLUTE));
    }

    @Test
    void idWithIllegalCharactersIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldTemplateRef("Arena Desert!", ABSOLUTE));
        assertThrows(IllegalArgumentException.class, () -> new WorldTemplateRef("arena/map", ABSOLUTE));
    }

    @Test
    void nullSourceIsRejected() {
        assertThrows(NullPointerException.class, () -> new WorldTemplateRef("arena-desert", null));
    }

    @Test
    void relativeSourceIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTemplateRef("arena-desert", Path.of("templates/arena.slime")));
    }
}
