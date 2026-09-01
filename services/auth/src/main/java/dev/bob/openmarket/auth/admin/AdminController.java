package dev.bob.openmarket.auth.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation + GDPR surface (Phase E). Authorization is role-based via the
 * JWT `roles` claim and the owner ⊃ admin ⊃ moderator hierarchy; 403s come
 * from @PreAuthorize through the standard `forbidden` envelope.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "admin")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    public record BanRequest(@NotBlank String reason, Instant expiresAt) {
    }

    public record WarnRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RolesRequest(@Valid List<String> roles) {
    }

    @GetMapping
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "List users (paged, optional query over email/name)")
    public AdminService.PageResult list(@RequestParam(required = false) String query,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return adminService.list(query, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "User detail: identity, roles, bans, warnings")
    public Map<String, Object> detail(@PathVariable UUID id) {
        return adminService.detail(id);
    }

    @PostMapping("/{id}/ban")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ban a user (revokes all sessions, emits user.banned)")
    public BanRequest ban(@PathVariable UUID id, @Valid @RequestBody BanRequest request,
                          Authentication authentication) {
        adminService.ban(currentUserId(authentication), id, request.reason(), request.expiresAt());
        return request;
    }

    @PostMapping("/{id}/unban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lift an active ban")
    public void unban(@PathVariable UUID id) {
        adminService.unban(id);
    }

    @PostMapping("/{id}/warn")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "Issue a warning")
    public Map<String, Object> warn(@PathVariable UUID id, @Valid @RequestBody WarnRequest request,
                                    Authentication authentication) {
        var warning = adminService.warn(currentUserId(authentication), id, request.reason());
        return Map.of("id", warning.getId(), "userId", warning.getUserId(),
            "reason", warning.getReason(), "createdAt", warning.getCreatedAt());
    }

    @PatchMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace the user's roles (future tokens only)")
    public Map<String, Object> setRoles(@PathVariable UUID id, @RequestBody RolesRequest request) {
        return Map.of("roles", adminService.setRoles(id, request.roles()));
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "GDPR data export (auth-owned slice)")
    public Map<String, Object> export(@PathVariable UUID id) {
        return adminService.export(id);
    }

    @PostMapping("/{id}/erase")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Erase a user: anonymize + revoke sessions + user.deleted event")
    public void erase(@PathVariable UUID id) {
        adminService.erase(id);
    }

    private static UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
