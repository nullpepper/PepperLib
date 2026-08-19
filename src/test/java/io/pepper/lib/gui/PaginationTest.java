package io.pepper.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaginationTest {

    private static final List<String> ITEMS = List.of("a", "b", "c", "d", "e", "f", "g");

    @Test
    void pageReturnsFirstPageSlice() {
        final PageWindow window = PageWindow.of(1, 3, 3);
        assertEquals(List.of("a", "b", "c"), Pagination.page(ITEMS, window));
    }

    @Test
    void pageReturnsMiddlePageSlice() {
        final PageWindow window = PageWindow.of(2, 3, 3);
        assertEquals(List.of("d", "e", "f"), Pagination.page(ITEMS, window));
    }

    @Test
    void pageReturnsLastPartialPage() {
        final PageWindow window = PageWindow.of(3, 3, 3);
        assertEquals(List.of("g"), Pagination.page(ITEMS, window));
    }

    @Test
    void pageReturnsEmptyForPageBeyondLast() {
        final PageWindow window = PageWindow.of(9, 9, 3);
        assertTrue(Pagination.page(ITEMS, window).isEmpty());
    }

    @Test
    void pageReturnsEmptyForEmptyList() {
        final PageWindow window = PageWindow.of(1, 1, 3);
        assertTrue(Pagination.page(List.of(), window).isEmpty());
        assertTrue(Pagination.page(null, window).isEmpty());
    }

    @Test
    void pageReturnsEmptyForZeroPageSize() {
        final PageWindow window = PageWindow.of(1, 3, 0);
        assertTrue(Pagination.page(ITEMS, window).isEmpty());
    }

    @Test
    void pageReturnsImmutableCopy() {
        final PageWindow window = PageWindow.of(1, 3, 3);
        final List<String> page = Pagination.page(ITEMS, window);
        assertThrows(UnsupportedOperationException.class, () -> page.add("x"));
    }

    @Test
    void itemAtReturnsItemAtSlot() {
        final PageWindow window = PageWindow.of(2, 3, 3);
        assertEquals("d", Pagination.itemAt(ITEMS, window, 0));
        assertEquals("f", Pagination.itemAt(ITEMS, window, 2));
    }

    @Test
    void itemAtReturnsNullForSlotBeyondPageContent() {
        final PageWindow window = PageWindow.of(3, 3, 3);
        assertNull(Pagination.itemAt(ITEMS, window, 1));
    }

    @Test
    void itemAtReturnsNullForPageBeyondLast() {
        final PageWindow window = PageWindow.of(9, 9, 3);
        assertNull(Pagination.itemAt(ITEMS, window, 0));
    }

    @Test
    void itemAtReturnsNullForEmptyList() {
        final PageWindow window = PageWindow.of(1, 1, 3);
        assertNull(Pagination.itemAt(List.of(), window, 0));
        assertNull(Pagination.itemAt(null, window, 0));
    }

    @Test
    void itemAtReturnsNullForNegativeSlot() {
        final PageWindow window = PageWindow.of(1, 3, 3);
        assertNull(Pagination.itemAt(ITEMS, window, -1));
    }

    @Test
    void itemAtReturnsNullForZeroPageSize() {
        final PageWindow window = PageWindow.of(1, 3, 0);
        assertNull(Pagination.itemAt(ITEMS, window, 0));
    }
}
