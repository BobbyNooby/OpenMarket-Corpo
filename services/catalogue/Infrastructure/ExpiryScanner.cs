using Catalogue.Infrastructure;
using Npgsql;

namespace Catalogue.Infrastructure;

// Flips active → expired when expires_at passes, emitting listing.expired
// into the outbox in the same transaction. Catch-up sweep on boot covers
// downtime; the advisory lock makes a second replica harmless; DB now()
// (via the UPDATE predicate) avoids app-clock skew.
public class ExpiryScanner(NpgsqlDataSource dataSource, ILogger<ExpiryScanner> logger) : BackgroundService
{
    private const int LockKey = 0x63617465; // 'cate'

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // catch-up sweep (covers downtime), then steady-state once a minute
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception e)
            {
                logger.LogError(e, "expiry sweep failed");
            }
            try
            {
                using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
                await timer.WaitForNextTickAsync(stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    public async Task SweepAsync(CancellationToken ct)
    {
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await using var lockCmd = conn.CreateCommand();
        lockCmd.CommandText = $"SELECT pg_try_advisory_lock({LockKey})";
        var gotLock = (bool)(await lockCmd.ExecuteScalarAsync(ct))!;
        if (!gotLock) return; // another replica is sweeping
        try
        {
            await using var tx = await conn.BeginTransactionAsync(ct);
            int expired;
            var outboxRows = new List<(Guid id, Guid authorId)>();
            await using (var cmd = conn.CreateCommand())
            {
                cmd.Transaction = tx;
                cmd.CommandText = """
                    UPDATE "Listings"
                       SET "Status" = 'Expired', "UpdatedAt" = now()
                     WHERE "Status" = 'Active' AND "ExpiresAt" IS NOT NULL AND "ExpiresAt" <= now()
                    RETURNING "Id", "AuthorId"
                    """;
                await using var reader = await cmd.ExecuteReaderAsync(ct);
                while (await reader.ReadAsync(ct))
                    outboxRows.Add((reader.GetGuid(0), reader.GetGuid(1)));
                expired = outboxRows.Count;
            }
            foreach (var (id, authorId) in outboxRows)
            {
                await using var cmd = conn.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = """
                    INSERT INTO "Outbox" ("Id", "AggregateType", "AggregateId", "Topic", "Payload", "CreatedAt")
                    VALUES (gen_random_uuid(), 'listing', @id, 'listing.expired',
                            jsonb_build_object('listingId', @id, 'authorId', @authorId), now())
                    """;
                cmd.Parameters.AddWithValue("id", id);
                cmd.Parameters.AddWithValue("authorId", authorId);
                await cmd.ExecuteNonQueryAsync(ct);
            }
            await tx.CommitAsync(ct);
            if (expired > 0)
                logger.LogInformation("expiry sweep: {count} listings expired", expired);
        }
        finally
        {
            await using var unlock = conn.CreateCommand();
            unlock.CommandText = $"SELECT pg_advisory_unlock({LockKey})";
            await unlock.ExecuteScalarAsync(CancellationToken.None);
        }
    }
}
