package com.company.pos.auth.web;

import com.company.pos.auth.api.AuthenticatedUser;
import com.company.pos.auth.application.AuthService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    TokenResponse login(@RequestBody LoginRequest request) {
        return new TokenResponse(authService.login(request.username(), request.password()));
    }

    @PostMapping("/pin-login")
    TokenResponse pinLogin(@RequestBody PinRequest request) {
        return new TokenResponse(authService.pinLogin(request.cashierCode(), request.pin()));
    }

    @GetMapping("/me")
    AuthenticatedUser me(JwtAuthenticationToken authentication) {
        Jwt jwt = authentication.getToken();
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new AuthenticatedUser(jwt.getSubject(), roles == null ? List.of() : roles);
    }

    @GetMapping("/manager-check")
    @PreAuthorize("hasRole('MANAGER')")
    String managerCheck() {
        return "ok";
    }

    public record LoginRequest(String username, String password) {
    }

    public record PinRequest(String cashierCode, String pin) {
    }

    public record TokenResponse(String token) {
    }
}
