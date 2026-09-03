using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Catalogue.Migrations
{
    /// <inheritdoc />
    public partial class InitialCreate : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "Categories",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    Name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    Slug = table.Column<string>(type: "text", nullable: false),
                    IconUrl = table.Column<string>(type: "text", nullable: true),
                    RetiredAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Categories", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Currencies",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    Slug = table.Column<string>(type: "text", nullable: false),
                    Name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    Description = table.Column<string>(type: "character varying(5000)", maxLength: 5000, nullable: true),
                    WikiLink = table.Column<string>(type: "text", nullable: true),
                    ImageUrl = table.Column<string>(type: "text", nullable: true),
                    RetiredAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Currencies", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "Outbox",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    AggregateType = table.Column<string>(type: "text", nullable: false),
                    AggregateId = table.Column<Guid>(type: "uuid", nullable: false),
                    Topic = table.Column<string>(type: "text", nullable: false),
                    Payload = table.Column<string>(type: "jsonb", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    PublishedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Outbox", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "UserItemLists",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    ListType = table.Column<string>(type: "text", nullable: false),
                    ItemId = table.Column<Guid>(type: "uuid", nullable: true),
                    CurrencyId = table.Column<Guid>(type: "uuid", nullable: true),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_UserItemLists", x => x.Id);
                    table.CheckConstraint("ck_user_item_lists_xor", "CAST((\"ItemId\" IS NOT NULL) AS INT) + CAST((\"CurrencyId\" IS NOT NULL) AS INT) = 1");
                });

            migrationBuilder.CreateTable(
                name: "Items",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    Slug = table.Column<string>(type: "text", nullable: false),
                    Name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    Description = table.Column<string>(type: "character varying(5000)", maxLength: 5000, nullable: true),
                    WikiLink = table.Column<string>(type: "text", nullable: true),
                    ImageUrl = table.Column<string>(type: "text", nullable: true),
                    CategoryId = table.Column<Guid>(type: "uuid", nullable: true),
                    RetiredAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Items", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Items_Categories_CategoryId",
                        column: x => x.CategoryId,
                        principalTable: "Categories",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "Listings",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    UpdatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    AuthorId = table.Column<Guid>(type: "uuid", nullable: false),
                    RequestedItemId = table.Column<Guid>(type: "uuid", nullable: true),
                    RequestedCurrencyId = table.Column<Guid>(type: "uuid", nullable: true),
                    Amount = table.Column<int>(type: "integer", nullable: false, defaultValue: 1),
                    OrderType = table.Column<string>(type: "text", nullable: false),
                    PayingType = table.Column<string>(type: "text", nullable: false),
                    Status = table.Column<string>(type: "text", nullable: false),
                    ExpiresAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true),
                    CancelledAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: true),
                    IdempotencyKey = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Listings", x => x.Id);
                    table.CheckConstraint("ck_listings_amount_positive", "\"Amount\" > 0");
                    table.CheckConstraint("ck_listings_requested_xor", "CAST((\"RequestedItemId\" IS NOT NULL) AS INT) + CAST((\"RequestedCurrencyId\" IS NOT NULL) AS INT) = 1");
                    table.ForeignKey(
                        name: "FK_Listings_Currencies_RequestedCurrencyId",
                        column: x => x.RequestedCurrencyId,
                        principalTable: "Currencies",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_Listings_Items_RequestedItemId",
                        column: x => x.RequestedItemId,
                        principalTable: "Items",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "OfferedCurrencies",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ListingId = table.Column<Guid>(type: "uuid", nullable: false),
                    CurrencyId = table.Column<Guid>(type: "uuid", nullable: false),
                    Amount = table.Column<int>(type: "integer", nullable: false, defaultValue: 1)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_OfferedCurrencies", x => x.Id);
                    table.CheckConstraint("ck_offered_currencies_amount_positive", "\"Amount\" > 0");
                    table.ForeignKey(
                        name: "FK_OfferedCurrencies_Currencies_CurrencyId",
                        column: x => x.CurrencyId,
                        principalTable: "Currencies",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_OfferedCurrencies_Listings_ListingId",
                        column: x => x.ListingId,
                        principalTable: "Listings",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "OfferedItems",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ListingId = table.Column<Guid>(type: "uuid", nullable: false),
                    ItemId = table.Column<Guid>(type: "uuid", nullable: false),
                    Amount = table.Column<int>(type: "integer", nullable: false, defaultValue: 1)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_OfferedItems", x => x.Id);
                    table.CheckConstraint("ck_offered_items_amount_positive", "\"Amount\" > 0");
                    table.ForeignKey(
                        name: "FK_OfferedItems_Items_ItemId",
                        column: x => x.ItemId,
                        principalTable: "Items",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_OfferedItems_Listings_ListingId",
                        column: x => x.ListingId,
                        principalTable: "Listings",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateTable(
                name: "Trades",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ListingId = table.Column<Guid>(type: "uuid", nullable: false),
                    SellerId = table.Column<Guid>(type: "uuid", nullable: false),
                    BuyerId = table.Column<Guid>(type: "uuid", nullable: false),
                    Snapshot = table.Column<string>(type: "jsonb", nullable: false),
                    CompletedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false),
                    AcceptedById = table.Column<Guid>(type: "uuid", nullable: false),
                    IdempotencyKey = table.Column<string>(type: "text", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Trades", x => x.Id);
                    table.ForeignKey(
                        name: "FK_Trades_Listings_ListingId",
                        column: x => x.ListingId,
                        principalTable: "Listings",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "Watchlist",
                columns: table => new
                {
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    ListingId = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAt = table.Column<DateTime>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_Watchlist", x => new { x.UserId, x.ListingId });
                    table.ForeignKey(
                        name: "FK_Watchlist_Listings_ListingId",
                        column: x => x.ListingId,
                        principalTable: "Listings",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_Categories_Slug",
                table: "Categories",
                column: "Slug",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Currencies_Slug",
                table: "Currencies",
                column: "Slug",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Items_CategoryId",
                table: "Items",
                column: "CategoryId");

            migrationBuilder.CreateIndex(
                name: "IX_Items_Slug",
                table: "Items",
                column: "Slug",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Listings_AuthorId",
                table: "Listings",
                column: "AuthorId");

            migrationBuilder.CreateIndex(
                name: "IX_Listings_AuthorId_IdempotencyKey",
                table: "Listings",
                columns: new[] { "AuthorId", "IdempotencyKey" },
                unique: true,
                filter: "\"IdempotencyKey\" <> ''");

            migrationBuilder.CreateIndex(
                name: "IX_Listings_RequestedCurrencyId",
                table: "Listings",
                column: "RequestedCurrencyId");

            migrationBuilder.CreateIndex(
                name: "IX_Listings_RequestedItemId",
                table: "Listings",
                column: "RequestedItemId");

            migrationBuilder.CreateIndex(
                name: "IX_Listings_Status_CreatedAt",
                table: "Listings",
                columns: new[] { "Status", "CreatedAt" });

            migrationBuilder.CreateIndex(
                name: "IX_Listings_Status_ExpiresAt",
                table: "Listings",
                columns: new[] { "Status", "ExpiresAt" });

            migrationBuilder.CreateIndex(
                name: "IX_OfferedCurrencies_CurrencyId",
                table: "OfferedCurrencies",
                column: "CurrencyId");

            migrationBuilder.CreateIndex(
                name: "IX_OfferedCurrencies_ListingId",
                table: "OfferedCurrencies",
                column: "ListingId");

            migrationBuilder.CreateIndex(
                name: "IX_OfferedItems_ItemId",
                table: "OfferedItems",
                column: "ItemId");

            migrationBuilder.CreateIndex(
                name: "IX_OfferedItems_ListingId",
                table: "OfferedItems",
                column: "ListingId");

            migrationBuilder.CreateIndex(
                name: "IX_Outbox_PublishedAt",
                table: "Outbox",
                column: "PublishedAt",
                filter: "\"PublishedAt\" IS NULL");

            migrationBuilder.CreateIndex(
                name: "IX_Trades_AcceptedById_IdempotencyKey",
                table: "Trades",
                columns: new[] { "AcceptedById", "IdempotencyKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_Trades_BuyerId",
                table: "Trades",
                column: "BuyerId");

            migrationBuilder.CreateIndex(
                name: "IX_Trades_CompletedAt",
                table: "Trades",
                column: "CompletedAt");

            migrationBuilder.CreateIndex(
                name: "IX_Trades_ListingId",
                table: "Trades",
                column: "ListingId");

            migrationBuilder.CreateIndex(
                name: "IX_Trades_SellerId",
                table: "Trades",
                column: "SellerId");

            migrationBuilder.CreateIndex(
                name: "IX_UserItemLists_CurrencyId",
                table: "UserItemLists",
                column: "CurrencyId");

            migrationBuilder.CreateIndex(
                name: "IX_UserItemLists_ItemId",
                table: "UserItemLists",
                column: "ItemId");

            migrationBuilder.CreateIndex(
                name: "IX_UserItemLists_UserId_ListType",
                table: "UserItemLists",
                columns: new[] { "UserId", "ListType" });

            migrationBuilder.CreateIndex(
                name: "IX_UserItemLists_UserId_ListType_CurrencyId",
                table: "UserItemLists",
                columns: new[] { "UserId", "ListType", "CurrencyId" },
                unique: true,
                filter: "\"CurrencyId\" IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "IX_UserItemLists_UserId_ListType_ItemId",
                table: "UserItemLists",
                columns: new[] { "UserId", "ListType", "ItemId" },
                unique: true,
                filter: "\"ItemId\" IS NOT NULL");

            migrationBuilder.CreateIndex(
                name: "IX_Watchlist_ListingId",
                table: "Watchlist",
                column: "ListingId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "OfferedCurrencies");

            migrationBuilder.DropTable(
                name: "OfferedItems");

            migrationBuilder.DropTable(
                name: "Outbox");

            migrationBuilder.DropTable(
                name: "Trades");

            migrationBuilder.DropTable(
                name: "UserItemLists");

            migrationBuilder.DropTable(
                name: "Watchlist");

            migrationBuilder.DropTable(
                name: "Listings");

            migrationBuilder.DropTable(
                name: "Currencies");

            migrationBuilder.DropTable(
                name: "Items");

            migrationBuilder.DropTable(
                name: "Categories");
        }
    }
}
