package dev.bob.openmarket.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users_activity")
public class UserActivity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    public UserActivity() {
    }

    public UserActivity(UUID userId) {
        this.userId = userId;
        this.isActive = false;
        this.lastActivityAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }
}