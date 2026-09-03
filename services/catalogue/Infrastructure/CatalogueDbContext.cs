using Catalogue.Domain;
using Microsoft.EntityFrameworkCore;
using Npgsql;

namespace Catalogue.Infrastructure;

public class CatalogueDbContext : DbContext
{
    public CatalogueDbContext(DbContextOptions<CatalogueDbContext> options) : base(options) { }

    public DbSet<ItemCategory> Categories => Set<ItemCategory>();
    public DbSet<Item> Items => Set<Item>();
    public DbSet<Currency> Currencies => Set<Currency>();
    public DbSet<Listing> Listings => Set<Listing>();
    public DbSet<ListingOfferedItem> OfferedItems => Set<ListingOfferedItem>();
    public DbSet<ListingOfferedCurrency> OfferedCurrencies => Set<ListingOfferedCurrency>();
    public DbSet<Trade> Trades => Set<Trade>();
    public DbSet<WatchlistEntry> Watchlist => Set<WatchlistEntry>();
    public DbSet<UserItemList> UserItemLists => Set<UserItemList>();
    public DbSet<OutboxEvent> Outbox => Set<OutboxEvent>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        // v2 corrections over the v1 contract: enums as text (migration-friendly),
        // owner columns uuid WITHOUT cross-service FKs (auth's users live in auth_db —
        // lifecycle arrives via user.deleted/user.banned events), CHECKs for the
        // marketplace invariants, timestamptz everywhere.

        b.Entity<ItemCategory>(e =>
        {
            e.ToTable("Categories");
            e.HasIndex(x => x.Slug).IsUnique();
            e.Property(x => x.Name).HasMaxLength(200);
        });

        b.Entity<Item>(e =>
        {
            e.ToTable("Items");
            e.HasIndex(x => x.Slug).IsUnique();
            e.Property(x => x.Name).HasMaxLength(200);
            e.Property(x => x.Description).HasMaxLength(5000);
            e.HasOne(x => x.Category).WithMany().HasForeignKey(x => x.CategoryId)
                .OnDelete(DeleteBehavior.Restrict); // v2: RESTRICT, never cascade shared defs
        });

        b.Entity<Currency>(e =>
        {
            e.ToTable("Currencies");
            e.HasIndex(x => x.Slug).IsUnique();
            e.Property(x => x.Name).HasMaxLength(200);
            e.Property(x => x.Description).HasMaxLength(5000);
        });

        b.Entity<Listing>(e =>
        {
            e.ToTable("Listings");
            e.ToTable(t => t.HasCheckConstraint(
                "ck_listings_requested_xor",
                "CAST((\"RequestedItemId\" IS NOT NULL) AS INT) + CAST((\"RequestedCurrencyId\" IS NOT NULL) AS INT) = 1"));
            e.ToTable(t => t.HasCheckConstraint("ck_listings_amount_positive", "\"Amount\" > 0"));
            e.HasIndex(x => new { x.Status, x.CreatedAt });
            e.HasIndex(x => new { x.Status, x.ExpiresAt });
            e.HasIndex(x => x.AuthorId);
            e.HasIndex(x => x.RequestedItemId);
            e.HasIndex(x => x.RequestedCurrencyId);
            e.HasIndex(x => new { x.AuthorId, x.IdempotencyKey }).IsUnique()
                .HasFilter("\"IdempotencyKey\" <> ''");
            e.Property(x => x.Amount).HasDefaultValue(1);
            // requested_* FKs are RESTRICT: retiring a shared definition must not
            // mass-delete listings (v1 cascaded — that was the defacement risk)
            e.HasOne(x => x.RequestedItem).WithMany()
                .HasForeignKey(x => x.RequestedItemId)
                .OnDelete(DeleteBehavior.Restrict);
            e.HasOne(x => x.RequestedCurrency).WithMany()
                .HasForeignKey(x => x.RequestedCurrencyId)
                .OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<ListingOfferedItem>(e =>
        {
            e.ToTable("OfferedItems");
            e.HasIndex(x => x.ListingId);
            e.HasIndex(x => x.ItemId); // reverse lookup: "listings offering item X"
            e.HasOne(x => x.Item).WithMany().HasForeignKey(x => x.ItemId)
                .OnDelete(DeleteBehavior.Restrict);
            e.HasOne(x => x.Listing).WithMany(l => l.OfferedItems)
                .HasForeignKey(x => x.ListingId)
                .OnDelete(DeleteBehavior.Cascade); // lines die with their listing — that's ownership, not coupling
            e.Property(x => x.Amount).HasDefaultValue(1);
            e.ToTable(t => t.HasCheckConstraint("ck_offered_items_amount_positive", "\"Amount\" > 0"));
        });

        b.Entity<ListingOfferedCurrency>(e =>
        {
            e.ToTable("OfferedCurrencies");
            e.HasIndex(x => x.ListingId);
            e.HasIndex(x => x.CurrencyId);
            e.HasOne(x => x.Currency).WithMany().HasForeignKey(x => x.CurrencyId)
                .OnDelete(DeleteBehavior.Restrict);
            e.HasOne(x => x.Listing).WithMany(l => l.OfferedCurrencies)
                .HasForeignKey(x => x.ListingId)
                .OnDelete(DeleteBehavior.Cascade);
            e.Property(x => x.Amount).HasDefaultValue(1);
            e.ToTable(t => t.HasCheckConstraint("ck_offered_currencies_amount_positive", "\"Amount\" > 0"));
        });

        b.Entity<Trade>(e =>
        {
            e.ToTable("Trades");
            e.Property(x => x.Snapshot).HasColumnType("jsonb");
            e.HasIndex(x => x.SellerId);
            e.HasIndex(x => x.BuyerId);
            e.HasIndex(x => x.CompletedAt);
            e.HasIndex(x => new { x.AcceptedById, x.IdempotencyKey }).IsUnique();
            e.HasOne<Listing>().WithMany().HasForeignKey(x => x.ListingId)
                .OnDelete(DeleteBehavior.Restrict); // v2 delta: trades link to their listing; no cascade
        });

        b.Entity<WatchlistEntry>(e =>
        {
            e.ToTable("Watchlist");
            e.HasKey(x => new { x.UserId, x.ListingId });
            e.HasOne(x => x.Listing).WithMany().HasForeignKey(x => x.ListingId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<UserItemList>(e =>
        {
            e.ToTable("UserItemLists");
            e.ToTable(t => t.HasCheckConstraint(
                "ck_user_item_lists_xor",
                "CAST((\"ItemId\" IS NOT NULL) AS INT) + CAST((\"CurrencyId\" IS NOT NULL) AS INT) = 1"));
            e.HasIndex(x => new { x.UserId, x.ListType });
            e.HasIndex(x => x.ItemId);
            e.HasIndex(x => x.CurrencyId);
            e.HasIndex(x => new { x.UserId, x.ListType, x.ItemId }).IsUnique()
                .HasFilter("\"ItemId\" IS NOT NULL");
            e.HasIndex(x => new { x.UserId, x.ListType, x.CurrencyId }).IsUnique()
                .HasFilter("\"CurrencyId\" IS NOT NULL");
        });

        b.Entity<OutboxEvent>(e =>
        {
            e.ToTable("Outbox");
            e.Property(x => x.Payload).HasColumnType("jsonb");
            // relay-ready: unpublished-first scan
            e.HasIndex(x => x.PublishedAt).HasFilter("\"PublishedAt\" IS NULL");
        });

        foreach (var et in new[] { typeof(Listing), typeof(ListingOfferedItem), typeof(ListingOfferedCurrency) })
        {
            // order_type / paying_type / status as readable text (v1-faithful enums)
        }
        b.Entity<Listing>().Property(x => x.OrderType).HasConversion<string>();
        b.Entity<Listing>().Property(x => x.PayingType).HasConversion<string>();
        b.Entity<Listing>().Property(x => x.Status).HasConversion<string>();
        b.Entity<UserItemList>().Property(x => x.ListType).HasConversion<string>();
    }
}
