package com.intermarche.fidelity.domain;

import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for all entities in the imfid loyalty domain.
 * <p>
 * Extends {@link io.quarkus.hibernate.orm.panache.PanacheEntity} to provide the
 * {@code id} field automatically and the Active Record pattern capabilities.
 * <p>
 * This class enforces standard auditing fields and data integrity for all
 * entities (same pattern as imvaluation's {@code BaseEntity}, §13, §17):
 * <ul>
 *   <li>id: Inherited from PanacheEntity (Long)</li>
 *   <li>version: For optimistic locking</li>
 *   <li>createdAt: Timestamp of creation</li>
 *   <li>updatedAt: Timestamp of last modification</li>
 *   <li>checksum: Hash of business fields for change detection</li>
 * </ul>
 * <p>
 * It uses {@link DateTimeProvider} instead of {@link LocalDateTime#now()} to
 * allow mocking time in tests (§24.6).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@MappedSuperclass
public abstract class BaseEntity extends PanacheEntity {

    /**
     * The version number for optimistic locking.
     * Automatically incremented by Hibernate when the entity is updated.
     * Essential for the per-card write serialization of the loyalty ledger (§30.1).
     */
    @Version
    public Long version;

    /**
     * The timestamp when this entity was first persisted to the database.
     * Once set, this field is never updated (updatable = false).
     */
    @Column(name = "created_at", updatable = false, nullable = false)
    public LocalDateTime createdAt;

    /**
     * The timestamp when this entity was last modified.
     * Updated automatically via JPA lifecycle callbacks.
     */
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    /**
     * The calculated checksum of the business fields.
     * <p>
     * Updated automatically via JPA lifecycle callbacks on every create or
     * update. It allows quick change detection and drives the staged CSV import
     * fallback (checksum optimization) without comparing all fields individually.
     * The value is derived from the {@link #getChecksum()} method implemented by
     * subclasses.
     */
    @Column(name = "checksum")
    public Integer checksum;

    /**
     * Lifecycle callback invoked before the entity is persisted (INSERT).
     * Initializes the creation and update timestamps using the DateTimeProvider
     * and stores the initial checksum.
     */
    @PrePersist
    public void onCreate() {
        LocalDateTime now = DateTimeProvider.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.checksum = getChecksum();
    }

    /**
     * Lifecycle callback invoked before the entity is updated (UPDATE).
     * Refreshes the update timestamp using the DateTimeProvider and recalculates
     * the checksum to reflect the change.
     */
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = DateTimeProvider.now();
        this.checksum = getChecksum();
    }

    // --------------------------------------------------
    // Equals and HashCode
    // --------------------------------------------------

    /**
     * Compares this entity to another object based on the class and the ID.
     * <p>
     * Two entities are equal only if they are the same instance, of the same
     * class, and carry the same non-null ID. New (transient) entities are never
     * equal to any other entity unless they are the same reference.
     *
     * @param o The object to compare.
     * @return true if the entities are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        return id != null && id.equals(that.id);
    }

    /**
     * Returns the hash code of the entity, combining its class and ID.
     * <p>
     * Because the ID is database-generated and null before persistence, a
     * transient entity must never be placed in a {@link java.util.Set} or used as
     * a {@link java.util.Map} key before being saved.
     *
     * @return The hash code based on class and ID.
     */
    @Override
    public int hashCode() {
        return Objects.hash(getClass(), id);
    }

    /**
     * Calculates a checksum representing the current business state of the entity.
     * <p>
     * Subclasses must implement this to define which fields constitute the
     * business identity. It is called by the JPA lifecycle callbacks to refresh
     * the persistent {@link #checksum} field.
     *
     * @return An integer checksum.
     */
    public abstract int getChecksum();
}
