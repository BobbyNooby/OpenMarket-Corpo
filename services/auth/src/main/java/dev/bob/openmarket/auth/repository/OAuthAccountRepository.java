package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    List<OAuthAccount> findByUserId(UUID userId);

    /** The Phase C lookup: "which user does this Discord account belong to?" */
    Optional<OAuthAccount> findByProviderAndProviderAccountId(String provider, String providerAccountId);
}
