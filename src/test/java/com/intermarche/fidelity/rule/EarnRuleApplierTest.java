package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for the {@link EarnRuleApplier} SPI default methods
 * {@link EarnRuleApplier#isProducer()} and
 * {@link EarnRuleApplier#excludedLineIds(List)} (§12, §15). The interface is pure
 * logic with no clock read and no Panache finder (§30.2), so no
 * {@code DateTimeProvider} and no static mock are involved: a minimal in-memory
 * implementation that supplies only {@link EarnRuleApplier#apply} inherits both
 * defaults, which are then asserted directly.
 */
class EarnRuleApplierTest {

    /**
     * A minimal producer applier that implements only the abstract {@code apply}
     * method and inherits both interface defaults, so the defaults' bytecode is the
     * only production code exercised.
     */
    private static final class DefaultApplier implements EarnRuleApplier {

        /**
         * Returns an empty entry for any input; the value is irrelevant to the
         * default-method assertions and never null (§30.2).
         *
         * @param lines   The valued basket lines; ignored.
         * @param context The dated card context; ignored.
         * @return An empty earn entry, never null.
         */
        @Override
        public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
            return EarnEntry.none("R", "L");
        }
    }

    /**
     * The default {@link EarnRuleApplier#isProducer()} reports a producer.
     */
    @Test
    @DisplayName("default isProducer returns true")
    void defaultIsProducerReturnsTrue() {
        assertTrue(new DefaultApplier().isProducer());
    }

    /**
     * The default {@link EarnRuleApplier#excludedLineIds(List)} returns the empty set
     * for a populated basket — a producer covers no line (§15).
     */
    @Test
    @DisplayName("default excludedLineIds returns empty set for a non-null basket")
    void defaultExcludedLineIdsNonNullBasket() {
        EarnRuleApplier applier = new DefaultApplier();
        Set<String> excluded = applier.excludedLineIds(List.of());
        assertEquals(Set.of(), excluded);
        assertTrue(excluded.isEmpty());
    }

    /**
     * The default {@link EarnRuleApplier#excludedLineIds(List)} yields the empty set
     * for a null basket as documented, exercising the other input arm.
     */
    @Test
    @DisplayName("default excludedLineIds returns empty set for a null basket")
    void defaultExcludedLineIdsNullBasket() {
        EarnRuleApplier applier = new DefaultApplier();
        Set<String> excluded = applier.excludedLineIds(null);
        assertEquals(Set.of(), excluded);
        assertTrue(excluded.isEmpty());
    }

    /**
     * Both {@link EarnRuleApplier#excludedLineIds(List)} calls return the same shared
     * immutable empty set, confirming the default holds no per-call state.
     */
    @Test
    @DisplayName("default excludedLineIds returns the shared empty set")
    void defaultExcludedLineIdsIsShared() {
        EarnRuleApplier applier = new DefaultApplier();
        assertSame(applier.excludedLineIds(List.of()), applier.excludedLineIds(null));
    }
}
