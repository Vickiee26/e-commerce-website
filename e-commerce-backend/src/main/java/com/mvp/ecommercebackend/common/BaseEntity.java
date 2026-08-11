package com.mvp.ecommercebackend.common;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;
import java.util.UUID;

/**
 * Identity and timestamps shared by every entity.
 *
 * <p>{@code equals}/{@code hashCode} compare the identifier only, which is why Lombok's
 * {@code @Data} must not be used on entities: its generated implementations ignore the
 * superclass and break collection semantics once an entity is persisted.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || !getClass().equals(other.getClass())) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? getClass().hashCode() : id.hashCode();
    }
}
