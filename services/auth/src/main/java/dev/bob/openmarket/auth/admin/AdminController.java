package dev.bob.openmarket.auth.admin;

import dev.bob.openmarket.auth.common.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * from @PreAuthorize through the standard `forbidden` envelope. The service
 * re-checks live DB roles and rank before acting, and threads actor + IP
 * into the audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "admin")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private final AdminService adminService;
    private final ClientIpResolver clientIp;

    public AdminController(AdminService adminService, ClientIpResolver clientIp) {
        this.adminService = adminService;
        this.clientIp = clientIp;
    }

    public record BanRequest(@NotBlank @Size(max = 500) String reason, Instant expiresAt) {
    }

    public record WarnRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RolesRequest(@NotNull List<@NotBlank @Size(max = 32) String> roles) {
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
                          Authentication authentication, HttpServletRequest http) {
        adminService.ban(currentUserId(authentication), id, request.reason(), request.expiresAt(),
            clientIp.resolve(http));
        return request;
    }

    @PostMapping("/{id}/unban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lift an active ban")
    public void unban(@PathVariable UUID id, Authentication authentication, HttpServletRequest http) {
        adminService.unban(currentUserId(authentication), id, clientIp.resolve(http));
    }

    @PostMapping("/{id}/warn")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MODERATOR')")
    @Operation(summary = "Issue a warning")
    public Map<String, Object> warn(@PathVariable UUID id, @Valid @RequestBody WarnRequest request,
                                    Authentication authentication, HttpServletRequest http) {
        var warning = adminService.warn(currentUserId(authentication), id, request.reason(),
            clientIp.resolve(http));
        return Map.of("id", warning.getId(), "userId", warning.getUserId(),
            "reason", warning.getReason(), "createdAt", warning.getCreatedAt());
    }

    @PatchMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace the user's roles (future tokens only)")
    public Map<String, Object> setRoles(@PathVariable UUID id,
                                        @Valid @RequestBody RolesRequest request,
                                        Authentication authentication, HttpServletRequest http) {
        return Map.of("roles", adminService.setRoles(currentUserId(authentication), id,
            request.roles(), clientIp.resolve(http)));
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
    public void erase(@PathVariable UUID id, Authentication authentication, HttpServletRequest http) {
        adminService.erase(currentUserId(authentication), id, clientIp.resolve(http));
    }

    private static UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
