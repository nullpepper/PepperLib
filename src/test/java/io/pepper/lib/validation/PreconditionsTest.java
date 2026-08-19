package io.pepper.lib.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PreconditionsTest {

    @Test
    void requireNonNullReturnsValueWhenNonNull() {
        final Object value = new Object();
        assertSame(value, Preconditions.requireNonNull(value, "value"));
    }

    @Test
    void requireNonNullRejectsNull() {
        assertThrows(NullPointerException.class, () -> Preconditions.requireNonNull(null, "value"));
    }

    @Test
    void requireNonNegativeAcceptsZeroAndPositive() {
        assertEquals(0, Preconditions.requireNonNegative(0, "count"));
        assertEquals(5, Preconditions.requireNonNegative(5, "count"));
    }

    @Test
    void requireNonNegativeRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNonNegative(-1, "count"));
    }

    @Test
    void requirePositiveAcceptsPositive() {
        assertEquals(1, Preconditions.requirePositive(1, "count"));
    }

    @Test
    void requirePositiveRejectsZeroAndNegative() {
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requirePositive(0, "count"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requirePositive(-3, "count"));
    }

    @Test
    void requireNotBlankAcceptsNonBlank() {
        assertEquals("abc", Preconditions.requireNotBlank("abc", "name"));
    }

    @Test
    void requireNotBlankRejectsBlankAndNull() {
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNotBlank("  ", "name"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNotBlank("", "name"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNotBlank(null, "name"));
    }

    @Test
    void messagesIncludeParameterName() {
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNonNegative(-1, "count"));
        assertEquals("count must be non-negative, got -1", e.getMessage());
    }
}
