package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.repository.RefreshTokenRepository;
import dev.bob.openmarket.auth.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;

/**
 * The sweep's contract: every delete runs with the documented 30-day
 * retention cutoff, never "now" — fresh-revoked rows are theft-forensics
 * evidence and must not be touched by the job that deletes them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenCleanupJobTest {

    @Mock RefreshTokenRepository refreshTokens;
    @Mock VerificationTokenRepository verificationTokens;

    private RefreshTokenCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new RefreshTokenCleanupJob(refreshTokens, verificationTokens);
    }

    @Test
    void retention_is_thirty_days() {
        assertThat(RefreshTokenCleanupJob.RETENTION).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void cleanup_deletes_expired_and_revoked_rows_with_the_retention_cutoff() {
        job.cleanupExpiredTokens();

        ArgumentCaptor<Instant> expiredCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> revokedCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokens).deleteExpiredBefore(expiredCutoff.capture());
        verify(refreshTokens).deleteRevokedBefore(revokedCutoff.capture());

        Instant expectedCutoff = Instant.now().minus(RefreshTokenCleanupJob.RETENTION);
        assertThat(expiredCutoff.getValue())
            .isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS)); // 30 days past expiry, not expiry itself
        assertThat(revokedCutoff.getValue())
            .isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS)); // fresh-revoked rows survive the sweep
    }

    @Test
    void cleanup_sweeps_dead_verification_tokens_with_the_same_cutoff() {
        job.cleanupExpiredTokens();

        ArgumentCaptor<Instant> usedCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiredCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(verificationTokens).deleteStaleBefore(usedCutoff.capture(), expiredCutoff.capture());

        Instant expectedCutoff = Instant.now().minus(RefreshTokenCleanupJob.RETENTION);
        assertThat(usedCutoff.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
        assertThat(expiredCutoff.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }
}
