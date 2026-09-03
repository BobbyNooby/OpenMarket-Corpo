using Catalogue.Domain;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace Catalogue.Infrastructure;

// Runs EF migrations at startup under a Postgres advisory lock — same
// lifecycle as auth's Flyway-on-startup (ready flips true only after), and
// safe if compose ever scales us. Also seeds the shared catalog so a fresh
// `make up` is not a dead marketplace.
public static class MigrationsRunner
{
    private const int LockKey = 0x6361746c; // 'catl'

    public static async Task RunAsync(NpgsqlDataSource dataSource, IServiceProvider services, ILogger logger, CancellationToken ct)
    {
        await using var conn = await dataSource.OpenConnectionAsync(ct);
        await Exec(conn, $"SELECT pg_advisory_lock({LockKey})", ct);
        try
        {
            var db = services.GetRequiredService<CatalogueDbContext>();
            await db.Database.MigrateAsync(ct);
            await Seeder.SeedAsync(db, ct);
            logger.LogInformation("migrations applied; catalog seeded");
        }
        finally
        {
            await Exec(conn, $"SELECT pg_advisory_unlock({LockKey})", CancellationToken.None);
        }
    }

    private static async Task Exec(NpgsqlConnection conn, string sql, CancellationToken ct)
    {
        await using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        await cmd.ExecuteNonQueryAsync(ct);
    }
}

internal static class Seeder
{
    // Idempotent: only fires on an empty catalog. Names/slugs are stable so
    // flow-test §15 (owner creates items) can coexist with these.
    public static async Task SeedAsync(CatalogueDbContext db, CancellationToken ct)
    {
        if (await db.Currencies.AnyAsync(ct)) return;

        Currency cur(string slug, string name) => new Currency { Slug = slug, Name = name };
        var currencies = new[]
        {
            cur("gold-coins", "Gold Coins"),
            cur("gems", "Gems"),
            cur("elixir-tokens", "Elixir Tokens"),
        };
        db.Currencies.AddRange(currencies);

        ItemCategory cat(string name, string slug) => new ItemCategory { Name = name, Slug = slug };
        var categories = new[]
        {
            cat("Weapons", "weapons"),
            cat("Armor", "armor"),
            cat("Consumables", "consumables"),
            cat("Materials", "materials"),
            cat("Pets", "pets"),
            cat("Mounts", "mounts"),
        };
        db.Categories.AddRange(categories);
        await db.SaveChangesAsync(ct); // need ids for category links

        var weapons = categories[0].Id;
        var armor = categories[1].Id;
        var consumables = categories[2].Id;
        var materials = categories[3].Id;
        var pets = categories[4].Id;
        var mounts = categories[5].Id;

        Item item(string name, string slug, Guid category, string description) =>
            new Item { Name = name, Slug = slug, CategoryId = category, Description = description };
        db.Items.AddRange(
            item("Iron Sword", "iron-sword", weapons, "Reliable starter blade."),
            item("Steel Longsword", "steel-longsword", weapons, "Balanced reach and weight."),
            item("Enchanted Blade", "enchanted-blade", weapons, "Hums faintly near mana."),
            item("Twin Daggers", "twin-daggers", weapons, "Fast, quiet, unforgiving."),
            item("Warhammer", "warhammer", weapons, "Arguments end when it arrives."),
            item("Iron Plate Set", "iron-plate-set", armor, "Full iron plate armor set."),
            item("Ranger Cloak", "ranger-cloak", armor, "Blends into treelines."),
            item("Dragonhide Vest", "dragonhide-vest", armor, "Scaly and stubborn."),
            item("Mithril Chainmail", "mithril-chainmail", armor, "Light as silk, hard as pride."),
            item("Tower Shield", "tower-shield", armor, "A wall you can carry."),
            item("Health Potion", "health-potion", consumables, "Tastes of berries and regret."),
            item("Mana Potion", "mana-potion", consumables, "Sparkling, slightly fizzy."),
            item("Elixir of Luck", "elixir-of-luck", consumables, "Placebo with excellent marketing."),
            item("Antidote", "antidote", consumables, "For when the mushroom was wrong."),
            item("Iron Ore", "iron-ore", materials, "Heavy and honest."),
            item("Mithril Ore", "mithril-ore", materials, "Shimmers under moonlight."),
            item("Dragon Scale", "dragon-scale", materials, "Still warm."),
            item("Leather Strips", "leather-strips", materials, "Crafting staple."),
            item("Enchanted Dust", "enchanted-dust", materials, "Do not inhale."),
            item("Ancient Relic", "ancient-relic", materials, "Purpose unknown, value certain."),
            item("Wolf Pup", "wolf-pup", pets, "Loyal to a fault."),
            item("Owl Companion", "owl-companion", pets, "Judges your schedule."),
            item("Mini Golem", "mini-golem", pets, "Mostly house-trained."),
            item("Fox Spirit", "fox-spirit", pets, "Smarter than advertised."),
            item("Warhorse", "warhorse", mounts, "Born for charge-ins."),
            item("Shadow Panther", "shadow-panther", mounts, "Purrs like thunder."),
            item("Royal Griffin", "royal-griffin", mounts, "Requires headroom."),
            item("Swift Elk", "swift-elk", mounts, "Never late."),
            item("Armored Boar", "armored-boar", mounts, "A battering ram with opinions."),
            item("Trading Cart", "trading-cart", mounts, "For the merchant lifestyle.")
        );
        await db.SaveChangesAsync(ct);
    }
}
