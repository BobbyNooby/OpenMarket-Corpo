package dev.bob.openmarket.auth.user;

import dev.bob.openmarket.auth.user.dto.MeResponse;
import dev.bob.openmarket.auth.user.dto.UpdateMeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The `sub` claim of a valid access token is the only identity input —
 * the controller never trusts a user id from the request body/path.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Your identity + profile")
    public MeResponse me(Authentication authentication) {
        return userService.me(currentUserId(authentication));
    }

    @PatchMapping("/me")
    @Operation(summary = "Update your profile (partial)")
    public MeResponse update(@Valid @RequestBody UpdateMeRequest request, Authentication authentication) {
        return userService.update(currentUserId(authentication), request);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete your account (soft delete, revokes all sessions)")
    public void delete(Authentication authentication) {
        userService.delete(currentUserId(authentication));
    }

    private static UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
