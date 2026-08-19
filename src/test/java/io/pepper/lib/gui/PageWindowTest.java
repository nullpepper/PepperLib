package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PageWindowTest {

    @Test
    void ofComputesStartIndexFromOneBasedPage() {
        final PageWindow window = PageWindow.of(3, 5, 10);
        assertEquals(3, window.page());
        assertEquals(5, window.maxPage());
        assertEquals(10, window.pageSize());
        assertEquals(20, window.startIndex());
    }

    @Test
    void ofClampsPageBelowOneToFirstPage() {
        final PageWindow window = PageWindow.of(0, 5, 10);
        assertEquals(1, window.page());
        assertEquals(0, window.startIndex());
    }

    @Test
    void ofClampsPageBeyondMaxToLastPage() {
        final PageWindow window = PageWindow.of(9, 5, 10);
        assertEquals(5, window.page());
        assertEquals(40, window.startIndex());
    }

    @Test
    void ofClampsMaxPageBelowOneToOne() {
        final PageWindow window = PageWindow.of(1, 0, 10);
        assertEquals(1, window.maxPage());
        assertEquals(1, window.page());
    }

    @Test
    void ofAllowsZeroPageSize() {
        final PageWindow window = PageWindow.of(1, 3, 0);
        assertEquals(0, window.pageSize());
        assertEquals(0, window.startIndex());
    }

    @Test
    void hasPreviousAndHasNext() {
        final PageWindow first = PageWindow.of(1, 3, 10);
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());

        final PageWindow middle = PageWindow.of(2, 3, 10);
        assertTrue(middle.hasPrevious());
        assertTrue(middle.hasNext());

        final PageWindow last = PageWindow.of(3, 3, 10);
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
    }

    @Test
    void singlePageWindowHasNoNavigation() {
        final PageWindow only = PageWindow.of(1, 1, 10);
        assertFalse(only.hasPrevious());
        assertFalse(only.hasNext());
    }

    @Test
    void constructorRejectsPageBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new PageWindow(0, 5, 10, 0));
    }

    @Test
    void constructorRejectsPageBeyondMax() {
        assertThrows(IllegalArgumentException.class, () -> new PageWindow(6, 5, 10, 50));
    }

    @Test
    void constructorRejectsNegativePageSize() {
        assertThrows(IllegalArgumentException.class, () -> new PageWindow(1, 5, -1, 0));
    }

    @Test
    void constructorRejectsInconsistentStartIndex() {
        assertThrows(IllegalArgumentException.class, () -> new PageWindow(2, 5, 10, 5));
    }

    // ------------------------------------------------------------------
    // fromZeroBased：0-based 页码 → 1-based 窗口（插件适配层用）
    // ------------------------------------------------------------------

    @Test
    void fromZeroBasedMapsFirstPage() {
        final PageWindow window = PageWindow.fromZeroBased(0, 5, 10);
        assertEquals(1, window.page());
        assertEquals(0, window.startIndex());
    }

    @Test
    void fromZeroBasedMapsLaterPage() {
        final PageWindow window = PageWindow.fromZeroBased(2, 5, 10);
        assertEquals(3, window.page());
        assertEquals(20, window.startIndex());
    }

    @Test
    void fromZeroBasedClampsNegativePageToFirstPage() {
        final PageWindow window = PageWindow.fromZeroBased(-3, 5, 10);
        assertEquals(1, window.page());
    }

    @Test
    void fromZeroBasedClampsPageBeyondMaxToLastPage() {
        final PageWindow window = PageWindow.fromZeroBased(99, 5, 10);
        assertEquals(5, window.page());
        assertEquals(40, window.startIndex());
    }
}
