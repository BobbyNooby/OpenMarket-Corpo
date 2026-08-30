package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, String> {
}
