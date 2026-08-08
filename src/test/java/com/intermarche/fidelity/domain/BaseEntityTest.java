package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.util.DateTimeProvider;
import java.time.LocalDateTime;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link BaseEntity}: the audit/optimistic-lock/checksum superclass every
 * imfid entity extends (§13, §17). The lifecycle callbacks read the clock only through
 * {@link DateTimeProvider}, so time is fixed on both sides of a border and never sampled from the
 * host clock (§24.6). Both arms of every guard and each leg of the compound {@code equals} guard
 * are exercised (§29, §29.6). The abstract class is instantiated through two distinct concrete
 * stubs so the {@code getClass()} identity check can be driven on both arms; no Quarkus boot and no
 * H2 are involved.
 */
class BaseEntityTest {

    /**
     * A minimal concrete {@link BaseEntity} whose checksum is a settable field, so lifecycle
     * callbacks can be observed writing a deterministic, mutable business hash.
     */
    static final class StubEntity extends BaseEntity {

        /**
         * The checksum this stub reports to the lifecycle callbacks.
         */
        int checksumValue;

        /**
         * Returns the stub's current business checksum.
         *
         * @return The configured checksum value.
         */
        @Override
        public int getChecksum() {
            return checksumValue;
        }
    }

    /**
     * A second, distinct concrete {@link BaseEntity} type used to drive the {@code getClass()}
     * mismatch leg of {@link BaseEntity#equals(Object)}.
     */
    static final class OtherStubEntity extends BaseEntity {

        /**
         * Returns a constant checksum; this stub only exists to differ in runtime class.
         *
         * @return A fixed checksum value.
         */
        @Override
        public int getChecksum() {
            return 0;
        }
    }

    /**
     * Clears any fixed instant so a later test never inherits this one's frozen clock.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // onCreate()
    // --------------------------------------------------

    /**
     * The persist callback stamps both timestamps with the fixed instant and snapshots the
     * checksum; time is read through {@link DateTimeProvider}, not the host clock.
     */
    @Test
    @DisplayName("onCreate(): stamps createdAt, updatedAt and checksum from the fixed instant")
    void onCreateStampsAudit() {
        LocalDateTime instant = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        DateTimeProvider.setFixedDateTime(instant);
        StubEntity entity = new StubEntity();
        entity.checksumValue = 42;
        entity.onCreate();
        assertEquals(instant, entity.createdAt);
        assertEquals(instant, entity.updatedAt);
        assertSame(entity.createdAt, entity.updatedAt);
        assertEquals(42, entity.checksum);
    }

    // --------------------------------------------------
    // onUpdate()
    // --------------------------------------------------

    /**
     * The update callback refreshes only {@code updatedAt} to the later instant and recomputes the
     * checksum, leaving the original {@code createdAt} untouched. The two instants straddle the
     * create/update border so the refresh is proven independent of the campaign's wall clock.
     */
    @Test
    @DisplayName("onUpdate(): refreshes updatedAt and checksum, preserves createdAt")
    void onUpdateRefreshesAudit() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        DateTimeProvider.setFixedDateTime(created);
        StubEntity entity = new StubEntity();
        entity.checksumValue = 42;
        entity.onCreate();
        LocalDateTime updated = LocalDateTime.of(2026, 1, 1, 0, 0, 1);
        DateTimeProvider.setFixedDateTime(updated);
        entity.checksumValue = 99;
        entity.onUpdate();
        assertEquals(created, entity.createdAt);
        assertEquals(updated, entity.updatedAt);
        assertEquals(99, entity.checksum);
    }

    // --------------------------------------------------
    // equals()
    // --------------------------------------------------

    /**
     * An entity equals itself — the {@code this == o} short-circuit true arm.
     */
    @Test
    @DisplayName("equals(): an entity equals itself (this == o)")
    void equalsSameReference() {
        StubEntity entity = new StubEntity();
        assertTrue(entity.equals(entity));
    }

    /**
     * An entity never equals {@code null} — the first leg of the compound guard true.
     */
    @Test
    @DisplayName("equals(): never equal to null (first leg)")
    void equalsNull() {
        StubEntity entity = new StubEntity();
        entity.id = 1L;
        assertFalse(entity.equals(null));
    }

    /**
     * An entity never equals an object of a different class — the second leg of the compound guard
     * true, first leg false.
     */
    @Test
    @DisplayName("equals(): never equal across classes (second leg)")
    void equalsDifferentClass() {
        StubEntity entity = new StubEntity();
        entity.id = 1L;
        OtherStubEntity other = new OtherStubEntity();
        other.id = 1L;
        assertFalse(entity.equals(other));
    }

    /**
     * A transient entity with a null id is never equal to another same-class entity — the
     * {@code id != null} guard's false arm, both guard legs false.
     */
    @Test
    @DisplayName("equals(): a null id is never equal (id != null false arm)")
    void equalsNullId() {
        StubEntity entity = new StubEntity();
        entity.id = null;
        StubEntity other = new StubEntity();
        other.id = 5L;
        assertFalse(entity.equals(other));
    }

    /**
     * Two same-class entities with the same non-null id are equal — {@code id != null} true and
     * {@code id.equals(that.id)} true.
     */
    @Test
    @DisplayName("equals(): same class and same id are equal")
    void equalsSameId() {
        StubEntity entity = new StubEntity();
        entity.id = 7L;
        StubEntity other = new StubEntity();
        other.id = 7L;
        assertTrue(entity.equals(other));
    }

    /**
     * Two same-class entities with different non-null ids are not equal — {@code id != null} true
     * and {@code id.equals(that.id)} false.
     */
    @Test
    @DisplayName("equals(): same class but different ids are not equal")
    void equalsDifferentId() {
        StubEntity entity = new StubEntity();
        entity.id = 7L;
        StubEntity other = new StubEntity();
        other.id = 8L;
        assertFalse(entity.equals(other));
    }

    // --------------------------------------------------
    // hashCode()
    // --------------------------------------------------

    /**
     * The hash combines the runtime class and the id, matching {@link Objects#hash(Object...)} over
     * the same pair for a persisted entity.
     */
    @Test
    @DisplayName("hashCode(): combines class and non-null id")
    void hashCodeWithId() {
        StubEntity entity = new StubEntity();
        entity.id = 3L;
        assertEquals(Objects.hash(StubEntity.class, 3L), entity.hashCode());
    }

    /**
     * The hash is stable for a transient entity whose id is still null — {@link Objects#hash} folds
     * the null id without raising.
     */
    @Test
    @DisplayName("hashCode(): stable for a null id")
    void hashCodeNullId() {
        StubEntity entity = new StubEntity();
        entity.id = null;
        assertEquals(Objects.hash(StubEntity.class, null), entity.hashCode());
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The abstract checksum contract returns the subclass-defined business hash verbatim.
     */
    @Test
    @DisplayName("getChecksum(): returns the subclass business hash")
    void checksumFromSubclass() {
        StubEntity entity = new StubEntity();
        entity.checksumValue = 123;
        assertEquals(123, entity.getChecksum());
        entity.checksumValue = -1;
        assertNotEquals(123, entity.getChecksum());
        assertNull(entity.version);
    }
}
