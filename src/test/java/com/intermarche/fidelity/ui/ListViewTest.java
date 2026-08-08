package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ListView}, the generic paginated-list view model backing every
 * administration screen (§21, §23). The class carries no clock and no Panache access: it is pure
 * presentation logic over the interaction state (filters, sort, pagination) and it builds every
 * link through the JAX-RS {@link jakarta.ws.rs.core.UriBuilder}, which resolves against the RESTEasy
 * runtime delegate on the plain test classpath (no {@code @QuarkusTest}, no H2). Views are built in
 * memory and each guard, ternary and compound leg is exercised on its own instance: the filter
 * lookup, the direction and notice ternaries, the compound {@code isFiltered} predicate, the
 * pagination flags, the summary and range sentences, the URL builders and the sort indicators.
 */
class ListViewTest {

    /**
     * The base path shared by the URL-building assertions.
     */
    private static final String BASE = "/ui/offers";

    /**
     * Builds a fully specified list view, every field controlled by the caller so each test isolates
     * the branch it targets.
     *
     * @param rows        The rows of the current page.
     * @param filters     The active filters, keyed by query parameter name.
     * @param sort        The active sort key.
     * @param descending  Whether the sort is descending.
     * @param currentPage The one-based number of the displayed page.
     * @param pageCount   The total number of pages.
     * @param totalCount  The total number of matching rows.
     * @param pageSize    The number of rows a full page holds.
     * @param itemLabel   The singular noun naming a row.
     * @param notice      A one-shot message to display, may be null.
     * @param noticeOk    Whether the message reports a success.
     * @param canWrite    Whether the signed-in user may modify the listed entities.
     * @return The assembled view model.
     */
    private static ListView<String> view(List<String> rows, Map<String, String> filters, String sort,
            boolean descending, int currentPage, int pageCount, long totalCount, int pageSize,
            String itemLabel, String notice, boolean noticeOk, boolean canWrite) {
        return new ListView<>(rows, BASE, filters, sort, descending, currentPage, pageCount,
                totalCount, pageSize, itemLabel, notice, noticeOk, canWrite);
    }

    /**
     * Asserts that {@link ListView#filter(String)} returns the stored value of a present filter and
     * an empty string for an absent one, covering both arms of its null ternary.
     */
    @Test
    @DisplayName("filter returns the value when present and empty string when absent")
    void filterBothArms() {
        ListView<String> v = view(List.of(), Map.of("status", "active"), "code", false, 1, 1, 1L, 20,
                "offer", null, false, false);
        assertEquals("active", v.filter("status"));
        assertEquals("", v.filter("missing"));
    }

    /**
     * Asserts that {@link ListView#getDirection()} yields "asc" on an ascending sort, the false arm
     * of its ternary, together with the plain accessors of the ascending state.
     */
    @Test
    @DisplayName("getDirection returns asc when ascending")
    void directionAscending() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("asc", v.getDirection());
        assertFalse(v.isDescending());
        assertEquals("code", v.getSort());
        assertEquals(List.of("r"), v.getRows());
    }

    /**
     * Asserts that {@link ListView#getDirection()} yields "desc" on a descending sort, the true arm
     * of its ternary.
     */
    @Test
    @DisplayName("getDirection returns desc when descending")
    void directionDescending() {
        ListView<String> v = view(List.of(), Map.of(), "code", true, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertEquals("desc", v.getDirection());
        assertTrue(v.isDescending());
    }

    /**
     * Asserts that a non-null notice is returned verbatim, that {@link ListView#isHasNotice()} then
     * reports its presence, and that {@link ListView#isNoticeOk()} carries the success flag, covering
     * the non-null arm of the notice ternary and the true arm of the presence guard.
     */
    @Test
    @DisplayName("getNotice returns the message and isHasNotice is true when a notice is present")
    void noticePresent() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", "Saved",
                true, false);
        assertEquals("Saved", v.getNotice());
        assertTrue(v.isHasNotice());
        assertTrue(v.isNoticeOk());
    }

    /**
     * Asserts that a null notice yields an empty string and that {@link ListView#isHasNotice()} then
     * reports its absence, covering the null arm of the notice ternary and the false arm of the
     * presence guard, together with the false success flag.
     */
    @Test
    @DisplayName("getNotice returns empty string and isHasNotice is false when notice is null")
    void noticeAbsent() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertEquals("", v.getNotice());
        assertFalse(v.isHasNotice());
        assertFalse(v.isNoticeOk());
    }

    /**
     * Asserts that {@link ListView#isCanWrite()} reflects the write flag on both settings.
     */
    @Test
    @DisplayName("isCanWrite reflects the write flag")
    void canWriteBothArms() {
        assertTrue(view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null, false, true)
                .isCanWrite());
        assertFalse(view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null, false,
                false).isCanWrite());
    }

    /**
     * Asserts that {@link ListView#isFiltered()} is true when a filter carries a non-blank value. The
     * map iterates a null value, then a blank value, then the non-blank one, so the single call
     * exercises all three legs of the {@code value != null && !value.isBlank()} predicate: null
     * (first leg false), blank (second leg false) and populated (both true, the match).
     */
    @Test
    @DisplayName("isFiltered is true when a filter carries a non-blank value")
    void filteredTrue() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("empty", null);
        filters.put("blank", "  ");
        filters.put("status", "active");
        ListView<String> v = view(List.of(), filters, "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertTrue(v.isFiltered());
    }

    /**
     * Asserts that {@link ListView#isFiltered()} is false when every filter is null or blank,
     * covering the predicate returning false on both the null leg and the blank leg.
     */
    @Test
    @DisplayName("isFiltered is false when every filter is null or blank")
    void filteredFalseNullAndBlank() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("empty", null);
        filters.put("blank", "  ");
        ListView<String> v = view(List.of(), filters, "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertFalse(v.isFiltered());
    }

    /**
     * Asserts that {@link ListView#isFiltered()} is false when there is no filter at all, covering the
     * empty-stream path where the predicate is never evaluated.
     */
    @Test
    @DisplayName("isFiltered is false when there is no filter")
    void filteredFalseEmpty() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertFalse(v.isFiltered());
    }

    /**
     * Asserts that {@link ListView#isPaged()} is true when at least one row matches, the true arm of
     * its {@code totalCount > 0} guard.
     */
    @Test
    @DisplayName("isPaged is true when totalCount is positive")
    void pagedTrue() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertTrue(v.isPaged());
    }

    /**
     * Asserts that {@link ListView#isPaged()} is false when nothing matches, the false arm of its
     * {@code totalCount > 0} guard.
     */
    @Test
    @DisplayName("isPaged is false when totalCount is zero")
    void pagedFalse() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertFalse(v.isPaged());
    }

    /**
     * Asserts that {@link ListView#isHasPrevious()} is false on the first page and true beyond it,
     * covering both arms of its {@code currentPage > 1} guard.
     */
    @Test
    @DisplayName("isHasPrevious is false on the first page and true beyond it")
    void hasPreviousBothArms() {
        assertFalse(view(List.of("r"), Map.of(), "code", false, 1, 3, 60L, 20, "offer", null, false,
                false).isHasPrevious());
        assertTrue(view(List.of("r"), Map.of(), "code", false, 2, 3, 60L, 20, "offer", null, false,
                false).isHasPrevious());
    }

    /**
     * Asserts that {@link ListView#isHasNext()} is true before the last page and false on it, covering
     * both arms of its {@code currentPage < pageCount} guard.
     */
    @Test
    @DisplayName("isHasNext is true before the last page and false on it")
    void hasNextBothArms() {
        assertTrue(view(List.of("r"), Map.of(), "code", false, 1, 3, 60L, 20, "offer", null, false,
                false).isHasNext());
        assertFalse(view(List.of("r"), Map.of(), "code", false, 3, 3, 60L, 20, "offer", null, false,
                false).isHasNext());
    }

    /**
     * Asserts that {@link ListView#getSummary()} states the empty result, the true arm of its
     * {@code totalCount == 0} guard.
     */
    @Test
    @DisplayName("getSummary states an empty result")
    void summaryEmpty() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertEquals("No offer", v.getSummary());
    }

    /**
     * Asserts that {@link ListView#getSummary()} uses the singular noun and omits the page position on
     * a single-page result, covering the empty arm of the plural ternary and the false arm of the
     * {@code pageCount > 1} guard.
     */
    @Test
    @DisplayName("getSummary is singular and page-less on a single result")
    void summarySingularSinglePage() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("1 offer", v.getSummary());
    }

    /**
     * Asserts that {@link ListView#getSummary()} pluralizes the noun and appends the page position on
     * a multi-page result, covering the "s" arm of the plural ternary and the true arm of the
     * {@code pageCount > 1} guard.
     */
    @Test
    @DisplayName("getSummary is plural and shows the page position on a multi-page result")
    void summaryPluralMultiPage() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 2, 3, 55L, 20, "offer", null,
                false, false);
        assertEquals("55 offers — page 2 of 3", v.getSummary());
    }

    /**
     * Asserts that {@link ListView#getRangeLabel()} is empty when nothing matches, the first leg of
     * its {@code totalCount == 0 || rows.isEmpty()} guard.
     */
    @Test
    @DisplayName("getRangeLabel is empty when totalCount is zero")
    void rangeLabelEmptyTotal() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 0L, 20, "offer", null,
                false, false);
        assertEquals("", v.getRangeLabel());
    }

    /**
     * Asserts that {@link ListView#getRangeLabel()} is empty when the page has no row despite a
     * positive total, the second leg of its guard (first leg false, second true).
     */
    @Test
    @DisplayName("getRangeLabel is empty when the page holds no row")
    void rangeLabelEmptyRows() {
        ListView<String> v = view(List.of(), Map.of(), "code", false, 1, 1, 5L, 20, "offer", null,
                false, false);
        assertEquals("", v.getRangeLabel());
    }

    /**
     * Asserts that {@link ListView#getRangeLabel()} states the covered range when both a positive
     * total and rows are present, the both-false path of the guard. The offset arithmetic is checked
     * on the second page: first = (2 - 1) * 20 + 1 = 21, last = 21 + 3 - 1 = 23.
     */
    @Test
    @DisplayName("getRangeLabel states the covered range on a populated page")
    void rangeLabelPopulated() {
        ListView<String> v = view(List.of("a", "b", "c"), Map.of(), "code", false, 2, 3, 55L, 20,
                "offer", null, false, false);
        assertEquals("Showing 21–23 of 55", v.getRangeLabel());
    }

    /**
     * Asserts that {@link ListView#pageUrl(int)} carries the sort and the populated filter while
     * dropping the null and blank ones, exercising all three legs of the {@code baseUrl} filter guard
     * (null skipped, blank skipped, non-blank kept) and appending the page parameter.
     */
    @Test
    @DisplayName("pageUrl keeps non-blank filters and the sort and drops null and blank filters")
    void pageUrlFiltersAndSort() {
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("empty", null);
        filters.put("blank", "  ");
        filters.put("status", "active");
        ListView<String> v = view(List.of("r"), filters, "code", false, 1, 3, 55L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers?status=active&sort=code&dir=asc&page=2", v.pageUrl(2));
    }

    /**
     * Asserts that {@link ListView#pageUrl(int)} of a view with no filter still carries the sort and
     * direction, covering the {@code baseUrl} loop over an empty filter map.
     */
    @Test
    @DisplayName("pageUrl carries the sort even with no filter")
    void pageUrlNoFilter() {
        ListView<String> v = view(List.of("r"), Map.of(), "name", true, 1, 2, 40L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers?sort=name&dir=desc&page=1", v.pageUrl(1));
    }

    /**
     * Asserts that {@link ListView#sortUrl(String)} reverses the active ascending column to
     * descending, the both-true path of {@code column.equals(sort) && !descending}.
     */
    @Test
    @DisplayName("sortUrl reverses the active ascending column to descending")
    void sortUrlReverseActiveAscending() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers?sort=code&dir=desc", v.sortUrl("code"));
    }

    /**
     * Asserts that {@link ListView#sortUrl(String)} on the active descending column sorts it ascending
     * again, covering the second leg false (descending true) of the reverse guard.
     */
    @Test
    @DisplayName("sortUrl on the active descending column returns to ascending")
    void sortUrlActiveDescending() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", true, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers?sort=code&dir=asc", v.sortUrl("code"));
    }

    /**
     * Asserts that {@link ListView#sortUrl(String)} on another column sorts it ascending, covering the
     * first leg false ({@code column != sort}) of the reverse guard.
     */
    @Test
    @DisplayName("sortUrl on another column sorts it ascending")
    void sortUrlOtherColumn() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers?sort=name&dir=asc", v.sortUrl("name"));
    }

    /**
     * Asserts that {@link ListView#actionUrl(String)} points at the sibling path while preserving the
     * sort and direction.
     */
    @Test
    @DisplayName("actionUrl targets the sibling path preserving the sort")
    void actionUrlSibling() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("/ui/offers/export?sort=code&dir=asc", v.actionUrl("export"));
    }

    /**
     * Asserts that {@link ListView#sortIndicator(String)} is empty on a column that is not the active
     * one, the true arm of its {@code !column.equals(sort)} guard.
     */
    @Test
    @DisplayName("sortIndicator is empty on an inactive column")
    void sortIndicatorInactive() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("", v.sortIndicator("name"));
    }

    /**
     * Asserts that {@link ListView#sortIndicator(String)} shows the down arrow on the active
     * descending column, the true arm of its direction ternary.
     */
    @Test
    @DisplayName("sortIndicator shows a down arrow on the active descending column")
    void sortIndicatorDescending() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", true, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("▾", v.sortIndicator("code"));
    }

    /**
     * Asserts that {@link ListView#sortIndicator(String)} shows the up arrow on the active ascending
     * column, the false arm of its direction ternary.
     */
    @Test
    @DisplayName("sortIndicator shows an up arrow on the active ascending column")
    void sortIndicatorAscending() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertEquals("▴", v.sortIndicator("code"));
    }

    /**
     * Asserts that {@link ListView#isSortedOn(String)} is true on the active column and false on
     * another, covering both arms of its equality guard.
     */
    @Test
    @DisplayName("isSortedOn is true on the active column and false on another")
    void sortedOnBothArms() {
        ListView<String> v = view(List.of("r"), Map.of(), "code", false, 1, 1, 1L, 20, "offer", null,
                false, false);
        assertTrue(v.isSortedOn("code"));
        assertFalse(v.isSortedOn("name"));
    }

    /**
     * Asserts that the remaining plain accessors return the values supplied to the constructor.
     */
    @Test
    @DisplayName("plain accessors return the constructor values")
    void plainAccessors() {
        ListView<String> v = view(List.of("a", "b"), Map.of(), "code", false, 2, 3, 55L, 20, "offer",
                null, false, false);
        assertEquals(2, v.getCurrentPage());
        assertEquals(3, v.getPageCount());
        assertEquals(55L, v.getTotalCount());
    }
}
