using System.Text.Json.Serialization;

namespace Catalogue.Domain;

// Catalog-wide shared definitions (not user-owned). Created by admin/owner,
// retired (hidden) instead of hard-deleted — listings referencing a retired
// definition stay visible but flagged, and new ones are refused.

public class ItemCategory
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public string Name { get; set; } = "";
    public string Slug { get; set; } = "";
    public string? IconUrl { get; set; }
    public DateTime? RetiredAt { get; set; }
}

public class Item
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public string Slug { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Description { get; set; }
    public string? WikiLink { get; set; }
    public string? ImageUrl { get; set; }
    public Guid? CategoryId { get; set; }
    public DateTime? RetiredAt { get; set; }
    public ItemCategory? Category { get; set; }
}

public class Currency
{
    public Guid Id { get; set; } = Guid.CreateVersion7();
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public string Slug { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Description { get; set; }
    public string? WikiLink { get; set; }
    public string? ImageUrl { get; set; }
    public DateTime? RetiredAt { get; set; }
}
