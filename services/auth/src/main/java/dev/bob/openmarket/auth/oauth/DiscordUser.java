package dev.bob.openmarket.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The slice of Discord's User object we consume
 * (https://discord.com/developers/docs/resources/user#user-object).
 * `id` is a snowflake — a string, never a number. `email`/`verified` only
 * appear with the `email` scope and a verified account.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscordUser(
    String id,
    String username,
    @JsonProperty("global_name") String globalName,
    String email,
    Boolean verified
) {

    /** Display name preference: global_name → username (never null/blank). */
    public String displayName() {
        return globalName != null && !globalName.isBlank() ? globalName : username;
    }

    /** Discord marks email trust with `verified`; we only link/verify on true. */
    public boolean hasVerifiedEmail() {
        return email != null && !email.isBlank() && Boolean.TRUE.equals(verified);
    }
}
