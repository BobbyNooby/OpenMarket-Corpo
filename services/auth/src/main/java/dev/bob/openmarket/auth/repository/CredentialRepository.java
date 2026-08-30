package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** PK is the user id — a user has at most one password credential. */
public interface CredentialRepository extends JpaRepository<Credential, UUID> {
}
