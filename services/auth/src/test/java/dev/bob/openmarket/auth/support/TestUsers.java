package dev.bob.openmarket.auth.support;

import dev.bob.openmarket.auth.domain.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

/** Shared fake identities for tests — keeps sub-ids consistent across stubs. */
public final class TestUsers {

    public static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TestUsers() {
    }

    public static User user() {
        return user(USER_ID, "garen@demaciabook.com");
    }

    public static User user(UUID id, String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Garen Crownguard");
        user.setEmailVerified(false);
        // id/createdAt/updatedAt are JPA-managed (no setters by design)
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "createdAt", Instant.now());
        ReflectionTestUtils.setField(user, "updatedAt", Instant.now());
        return user;
    }
}
