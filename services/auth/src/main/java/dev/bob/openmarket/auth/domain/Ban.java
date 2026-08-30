package dev.bob.openmarket.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A moderation ban. Active while `lifted_at` is null and (expires_at is null or future). */
@Entity
@Table(name = "user_bans")
public class Ban {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "banned_by")
    private UUID bannedBy;

    @Column
    private String reason;

    @Column(name = "banned_at", nullable = false, updatable = false)
    private Instant bannedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @PrePersist
    void onCreate() {
        this.bannedAt = Instant.now();
    }

    public boolean isActive(Instant now) {
        return liftedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getBannedBy() { return bannedBy; }
    public void setBannedBy(UUID bannedBy) { this.bannedBy = bannedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getBannedAt() { return bannedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLiftedAt() { return liftedAt; }
    public void setLiftedAt(Instant liftedAt) { this.liftedAt = liftedAt; }
}
