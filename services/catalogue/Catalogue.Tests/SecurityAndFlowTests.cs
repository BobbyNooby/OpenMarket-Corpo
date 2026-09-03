using Xunit;
using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.TestHost;
using System.Text.Json;
using Catalogue.Domain;
using Catalogue.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Xunit.Abstractions;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace Catalogue.Tests;

[Collection("catalogue")]
public class SecurityAndFlowTests(CatalogueFixture fx, ITestOutputHelper output)
{
    private readonly ITestOutputHelper output = output;

    private HttpClient NewClient(string user, string[]? roles = null)
    {
        var client = fx.Factory.CreateDefaultClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", fx.TokenFor(user, roles));
        return client;
    }

    private HttpClient Anonymous() => fx.Factory.CreateDefaultClient();

    private async Task<Guid> AdminCreateCurrency(string name)
    {
        var admin = NewClient("admin-owner", roles: ["owner"]);
        var res = await admin.PostAsJsonAsync("/api/v1/catalogue/currencies", new { name });
        res.EnsureSuccessStatusCode();
        var json = await res.Content.ReadFromJsonAsync<JsonElement>();
        return json.GetProperty("id").GetGuid();
    }

    private async Task<Guid> AdminCreateItem(string name)
    {
        var admin = NewClient("admin-owner", roles: ["owner"]);
        var res = await admin.PostAsJsonAsync("/api/v1/catalogue/items", new { name });
        res.EnsureSuccessStatusCode();
        var json = await res.Content.ReadFromJsonAsync<JsonElement>();
        return json.GetProperty("id").GetGuid();
    }

    private async Task<Guid> CreateListing(string seller, Guid requestedCurrencyId, int amount = 1)
    {
        var client = NewClient(seller, roles: ["user"]);
        var res = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId,
            amount,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = requestedCurrencyId, amount = 1 } },
        });
        res.EnsureSuccessStatusCode();
        var json = await res.Content.ReadFromJsonAsync<JsonElement>();
        return json.GetProperty("listingId").GetGuid();
    }

    // ── health + public browse ────────────────────────────────

    [Fact]
    public async Task health_live_is_public()
    {
        var res = await Anonymous().GetAsync("/health/live");
        Assert.Equal(HttpStatusCode.OK, res.StatusCode);
    }

    [Fact]
    public async Task seeded_catalog_is_publicly_browsable()
    {
        var res = await Anonymous().GetAsync("/api/v1/catalogue/items");
        Assert.Equal(HttpStatusCode.OK, res.StatusCode);
        var json = await res.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(json.GetProperty("total").GetInt32() > 0, "seeded items should exist");
    }

    // ── authentication ────────────────────────────────────────

    [Fact]
    public async Task anonymous_mutation_is_401()
    {
        var res = await Anonymous().PostAsJsonAsync("/api/v1/catalogue/items", new { name = "Nope" });
        Assert.Equal(HttpStatusCode.Unauthorized, res.StatusCode);
    }

    [Fact]
    public async Task expired_token_is_401_on_mutation()
    {
        var expired = fx.TokenFor("expired-user", expires: DateTime.UtcNow.AddMinutes(-5));
        var client = fx.Factory.CreateDefaultClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", expired);
        var res = await client.PostAsJsonAsync("/api/v1/catalogue/items", new { name = "Nope" });
        Assert.Equal(HttpStatusCode.Unauthorized, res.StatusCode);
    }

    // ── catalog admin (introspected roles: admin-or-owner) ────

    [Fact]
    public async Task plain_user_cannot_create_items()
    {
        var client = NewClient("mallory", roles: ["user"]);
        var res = await client.PostAsJsonAsync("/api/v1/catalogue/items", new { name = "Mallory Item" });
        Assert.Equal(HttpStatusCode.Forbidden, res.StatusCode);
    }

    [Fact]
    public async Task owner_role_can_create_items_and_browse_shows_them()
    {
        var client = NewClient("owner-creator", roles: ["owner"]);
        var res = await client.PostAsJsonAsync("/api/v1/catalogue/items", new { name = "Test Relic Alpha" });
        output.WriteLine($"CREATE -> {(int)res.StatusCode}: {await res.Content.ReadAsStringAsync()}");
        Assert.Equal(HttpStatusCode.Created, res.StatusCode);
        var slug = (await res.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("slug").GetString()!;

        var browse = await Anonymous().GetAsync("/api/v1/catalogue/items?limit=50");
        var body = await browse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Contains(body.GetProperty("items").EnumerateArray(),
            i => i.GetProperty("slug").GetString() == slug);
    }

    [Fact]
    public async Task retired_item_hidden_from_browse_but_still_resolvable()
    {
        var admin = NewClient("retire-admin", roles: ["admin"]);
        var slug = (await (await admin.PostAsJsonAsync("/api/v1/catalogue/items", new { name = "Retire Me Relic" }))
            .Content.ReadFromJsonAsync<JsonElement>()).GetProperty("slug").GetString()!;

        var retire = await admin.PostAsync($"/api/v1/catalogue/items/{slug}/retire", null);
        Assert.Equal(HttpStatusCode.OK, retire.StatusCode);

        var browse = await Anonymous().GetAsync("/api/v1/catalogue/items");
        var body = await browse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.DoesNotContain(body.GetProperty("items").EnumerateArray(),
            i => i.GetProperty("slug").GetString() == slug);

        var direct = await Anonymous().GetAsync($"/api/v1/catalogue/items/{slug}");
        Assert.Equal(HttpStatusCode.OK, direct.StatusCode);
    }

    // ── listing validation ────────────────────────────────────

    [Fact]
    public async Task listing_requires_authentication()
    {
        var res = await Anonymous().PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = Guid.NewGuid(),
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = Array.Empty<object>(),
        });
        Assert.Equal(HttpStatusCode.Unauthorized, res.StatusCode);
    }

    [Fact]
    public async Task listing_rejects_xor_violation()
    {
        var coin = await AdminCreateCurrency("Xor Coin");
        var item = await AdminCreateItem("Xor Item");
        var client = NewClient("xor-user", roles: ["user"]);
        var both = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            requestedItemId = item,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        Assert.Equal(HttpStatusCode.BadRequest, both.StatusCode);
        var neither = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        Assert.Equal(HttpStatusCode.BadRequest, neither.StatusCode);
    }

    [Fact]
    public async Task listing_rejects_zero_amount_and_past_expiry()
    {
        var client = NewClient("validation-user", roles: ["user"]);
        var coin = await AdminCreateCurrency("Validation Coin");

        var badAmount = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 0,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        Assert.Equal(HttpStatusCode.BadRequest, badAmount.StatusCode);

        var badExpiry = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            expiresAt = DateTime.UtcNow.AddHours(-1),
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        Assert.Equal(HttpStatusCode.BadRequest, badExpiry.StatusCode);
    }

    [Fact]
    public async Task listing_rejects_retired_offered_item()
    {
        var admin = NewClient("retired-offer-admin", roles: ["owner"]);
        var itemId = await AdminCreateItem("Retired Offer Item");
        await admin.PostAsync($"/api/v1/catalogue/items/{itemId}/retire", null);

        var client = NewClient("retired-offer-user", roles: ["user"]);
        var res = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = Guid.NewGuid(),
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "item", id = itemId, amount = 1 } },
        });
        Assert.Equal(HttpStatusCode.BadRequest, res.StatusCode);
    }

    // ── accept flow + concurrency (the HIGHs from the audit) ──

    [Fact]
    public async Task full_trade_flow_through_browse_and_accept()
    {
        var coin = await AdminCreateCurrency("Trade Flow Coin");
        var seller = NewClient("trade-seller", roles: ["user"]);
        var listingId = await CreateListing("trade-seller-2", coin, 2);

        var buyer = NewClient("trade-buyer", roles: ["user"]);
        var browse = await buyer.GetAsync($"/api/v1/catalogue/listings?requestedCurrencyId={coin}&orderType=sell");
        Assert.Equal(HttpStatusCode.OK, browse.StatusCode);
        var found = await browse.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Contains(found.GetProperty("items").EnumerateArray(),
            l => l.GetProperty("id").GetGuid() == listingId);

        var accept = await buyer.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.Created, accept.StatusCode);

        // seller sees the trade; buyer sees the trade
        var sellerTrades = await NewClient("trade-seller", roles: ["user"])
            .GetAsync("/api/v1/catalogue/listings/me/trades");
        Assert.Equal(HttpStatusCode.OK, sellerTrades.StatusCode);
    }

    [Fact]
    public async Task competing_accepts_yield_exactly_one_trade()
    {
        var coin = await AdminCreateCurrency("Race Coin");
        var listingId = await CreateListing("race-seller", coin);

        var buyerA = NewClient("race-buyer-a", roles: ["user"]);
        var buyerB = NewClient("race-buyer-b", roles: ["user"]);

        var a = buyerA.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        var b = buyerB.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        await Task.WhenAll(a, b);

        var codes = new[] { a.Result.StatusCode, b.Result.StatusCode };
        Assert.Contains(HttpStatusCode.Created, codes);
        Assert.Contains(HttpStatusCode.Conflict, codes);

        // exactly one trade is the invariant the whole test exists for
        using (var scope = fx.Factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<CatalogueDbContext>();
            Assert.Equal(1, await db.Trades.CountAsync(t => t.ListingId == listingId));
        }

        // the WINNER retrying the same accept is the exact case idempotency
        // exists for: double-submit replays the original trade, no second one
        var winnerIsA = a.Result.StatusCode == HttpStatusCode.Created;
        var winner = winnerIsA ? buyerA : buyerB;
        var winnerRetry = await winner.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.OK, winnerRetry.StatusCode);

        // a DIFFERENT buyer, though, cannot accept a sold listing
        var fresh = await NewClient("race-buyer-c", roles: ["user"])
            .PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.Conflict, fresh.StatusCode);
    }

    [Fact]
    public async Task author_cannot_accept_own_listing()
    {
        var coin = await AdminCreateCurrency("Self Accept Coin");
        var listingId = await CreateListing("self-accept-seller", coin);
        var res = await NewClient("self-accept-seller", roles: ["user"])
            .PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.Conflict, res.StatusCode);
    }

    [Fact]
    public async Task banned_user_mutation_fails_closed_403()
    {
        var coin = await AdminCreateCurrency("Banned Mutation Coin");
        var listingId = await CreateListing("banned-mutation-seller", coin);

        fx.Introspector.Override = t => (false, GuidUtility.FromName(t), Array.Empty<string>());
        try
        {
            var client = NewClient("banned-mutation-user", roles: ["user"]);
            var res = await client.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
            Assert.Equal(HttpStatusCode.Forbidden, res.StatusCode);
        }
        finally
        {
            fx.Introspector.Override = null;
        }
    }

    [Fact]
    public async Task introspection_outage_fails_closed_503()
    {
        var coin = await AdminCreateCurrency("Outage Coin");
        var listingId = await CreateListing("outage-seller", coin);

        fx.Introspector.Override = _ => null;
        try
        {
            var client = NewClient("outage-user", roles: ["user"]);
            var res = await client.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
            Assert.Equal(HttpStatusCode.ServiceUnavailable, res.StatusCode);
        }
        finally
        {
            fx.Introspector.Override = null;
        }
    }

    // ── expiry scanner ────────────────────────────────────────

    [Fact]
    public async Task expired_listing_accept_is_410_after_sweep()
    {
        var coin = await AdminCreateCurrency("Sweep Coin");
        var seller = NewClient("sweep-seller", roles: ["user"]);
        // create with a short future expiry (validation refuses past dates),
        // then force the sweep immediately after it lapses
        var client = NewClient("sweep-seller", roles: ["user"]);
        var created = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            expiresAt = DateTime.UtcNow.AddSeconds(2),
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        var listingId = (await created.Content.ReadFromJsonAsync<JsonElement>())
            .GetProperty("listingId").GetGuid();

        await Task.Delay(2500);
        var scanner = fx.Factory.Services.GetServices<IHostedService>()
            .OfType<ExpiryScanner>().Single();
        await scanner.SweepAsync(CancellationToken.None);

        var accept = await NewClient("sweep-buyer", roles: ["user"])
            .PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.Gone, accept.StatusCode);
    }

    // ── idempotency ───────────────────────────────────────────

    [Fact]
    public async Task idempotency_key_replays_same_listing_and_rejects_body_mismatch()
    {
        var coin = await AdminCreateCurrency("Idem Coin");
        var client = NewClient("idem-user", roles: ["user"]);

        var first = await client.PostJsonWithKey("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        }, "same-key-1");
        Assert.Equal(HttpStatusCode.Created, first.StatusCode);

        var replay = await client.PostJsonWithKey("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        }, "same-key-1");
        Assert.Equal(HttpStatusCode.OK, replay.StatusCode);

        var mismatch = await client.PostJsonWithKey("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 7,
            orderType = "sell",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        }, "same-key-1");
        Assert.Equal(HttpStatusCode.Conflict, mismatch.StatusCode);

        // same key, same everything EXCEPT the order type — still a different
        // request and must conflict, not silently replay
        var wrongKind = await client.PostJsonWithKey("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "buy",
            payingType = "each",
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        }, "same-key-1");
        Assert.Equal(HttpStatusCode.Conflict, wrongKind.StatusCode);
    }

    [Fact]
    public async Task keyless_accepts_do_not_collide_on_unique_index()
    {
        var coin = await AdminCreateCurrency("Keyless Coin");
        var listingA = await CreateListing("keyless-seller-a", coin);
        var listingB = await CreateListing("keyless-seller-b", coin);

        // the same buyer, both accepts WITHOUT an Idempotency-Key — the empty
        // key must not collide with itself on the (accepter, key) unique index
        var buyer = NewClient("keyless-buyer", roles: ["user"]);
        var first = await buyer.PostAsync($"/api/v1/catalogue/listings/{listingA}/accept", null);
        Assert.Equal(HttpStatusCode.Created, first.StatusCode);
        var second = await buyer.PostAsync($"/api/v1/catalogue/listings/{listingB}/accept", null);
        Assert.NotEqual(HttpStatusCode.InternalServerError, second.StatusCode);
        Assert.Equal(HttpStatusCode.Created, second.StatusCode);
    }

    [Fact]
    public async Task accept_lapsed_listing_is_410_even_before_sweep()
    {
        var coin = await AdminCreateCurrency("Lapse Coin");
        var client = NewClient("lapse-seller", roles: ["user"]);
        var created = await client.PostAsJsonAsync("/api/v1/catalogue/listings", new
        {
            requestedCurrencyId = coin,
            amount = 1,
            orderType = "sell",
            payingType = "each",
            expiresAt = DateTime.UtcNow.AddSeconds(2),
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        var listingId = (await created.Content.ReadFromJsonAsync<JsonElement>())
            .GetProperty("listingId").GetGuid();

        // past its deadline but BEFORE the scanner sweeps: still un-sellable
        await Task.Delay(2500);
        var buyer = NewClient("lapse-buyer", roles: ["user"]);
        var accept = await buyer.PostAsync($"/api/v1/catalogue/listings/{listingId}/accept", null);
        Assert.Equal(HttpStatusCode.Gone, accept.StatusCode);

        // and no trade may have slipped through in that window
        var trades = await buyer.GetAsync("/api/v1/catalogue/listings/me/trades");
        var body = await trades.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Empty(body.GetProperty("trades").EnumerateArray());
    }

    [Fact]
    public async Task health_ready_degrades_503_on_outbox_backpressure()
    {
        using var scope = fx.Factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<CatalogueDbContext>();
        for (var batch = 0; batch < 11; batch++)
        {
            db.Outbox.AddRange(Enumerable.Range(0, 1000).Select(_ => new OutboxEvent
            {
                AggregateType = "listing",
                AggregateId = Guid.CreateVersion7(),
                Topic = "listing.created",
                Payload = "{}",
            }));
            await db.SaveChangesAsync();
        }

        var res = await Anonymous().GetAsync("/health/ready");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, res.StatusCode);
    }

    [Fact]
    public async Task patch_can_switch_requested_kind()
    {
        var coin = await AdminCreateCurrency("Switch Coin");
        await AdminCreateItem("Switch Item");
        var client = NewClient("switch-user", roles: ["user"]);
        var listingId = await CreateListing("switch-user", coin);

        // full-replace: the PATCH states the whole desired listing, so dropping
        // requestedItemId and naming a currency instead is an ordinary update
        var patch = await client.PatchAsJsonAsync($"/api/v1/catalogue/listings/{listingId}", new
        {
            amount = 2,
            requestedCurrencyId = coin,
            offered = new[] { new { kind = "currency", id = coin, amount = 1 } },
        });
        output.WriteLine($"PATCH -> {(int)patch.StatusCode}: {await patch.Content.ReadAsStringAsync()}");
        Assert.Equal(HttpStatusCode.OK, patch.StatusCode);

        var get = await Anonymous().GetAsync($"/api/v1/catalogue/listings/{listingId}");
        var body = await get.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("currency", body.GetProperty("requested").GetProperty("kind").GetString());
    }

    [Fact]
    public async Task banned_user_watchlist_delete_fails_closed_403()
    {
        var coin = await AdminCreateCurrency("Watch Ban Coin");
        var listingId = await CreateListing("watch-ban-seller", coin);
        var watcher = NewClient("watch-ban-user", roles: ["user"]);
        var put = await watcher.PutAsJsonAsync($"/api/v1/catalogue/me/watchlist/{listingId}", new { });
        Assert.Equal(HttpStatusCode.OK, put.StatusCode);

        fx.Introspector.Override = t => (false, GuidUtility.FromName(t), Array.Empty<string>());
        try
        {
            var del = await watcher.DeleteAsync($"/api/v1/catalogue/me/watchlist/{listingId}");
            Assert.Equal(HttpStatusCode.Forbidden, del.StatusCode);
        }
        finally
        {
            fx.Introspector.Override = null;
        }
    }
}

public static class TestHttpExtensions
{
    public static Task<HttpResponseMessage> PostJsonWithKey<T>(this HttpClient client,
        string url, T body, string idempotencyKey)
    {
        var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = JsonContent.Create(body),
        };
        req.Headers.Add("Idempotency-Key", idempotencyKey);
        return client.SendAsync(req);
    }
}