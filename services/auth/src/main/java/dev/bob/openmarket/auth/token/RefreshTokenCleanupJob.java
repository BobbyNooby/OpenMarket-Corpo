package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.repository.RefreshTokenRepository;
import dev.bob.openmarket.auth.repository.VerificationTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Daily 04:00 UTC sweep of dead token rows. Everything here works on a
 * retention window measured from the row's moment of death, never "dead
 * means delete now": revoked refresh tokens are theft-forensics evidence
 * (a reuse incident is reconstructed from who revoked what, when), and
 * recently-expired rows are cheap to keep for support questions. Freshly
 * dead rows are therefore untouched by this job — only rows that have been
 * dead for {@link #RETENTION} get removed.
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    /**
     * How long a dead row (expired, revoked, or consumed) is kept before the
     * sweep deletes it. 30 days covers any realistic forensics/support need
     * for token rows while bounding table growth from rotation churn.
     */
    static final Duration RETENTION = Duration.ofDays(30);

    private final RefreshTokenRepository refreshTokens;
    private final VerificationTokenRepository verificationTokens;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokens,
                                  VerificationTokenRepository verificationTokens) {
        this.refreshTokens = refreshTokens;
        this.verificationTokens = verificationTokens;
    }

    /** Bulk deletes only; one log line with the row counts. */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @Transactional
    public void cleanupExpiredTokens() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int refresh = refreshTokens.deleteExpiredBefore(cutoff)
            + refreshTokens.deleteRevokedBefore(cutoff);
        int verification = verificationTokens.deleteStaleBefore(cutoff, cutoff);
        log.info("[cleanup] removed {} refresh token rows and {} verification token rows dead for over {}",
            refresh, verification, RETENTION);
    }
}
