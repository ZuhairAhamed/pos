package com.company.pos.auth.web;

import com.company.pos.auth.application.AuthService;
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

    public record LoginRequest(String username, String password) {
    }

    public record PinRequest(String cashierCode, String pin) {
    }

    public record TokenResponse(String token) {
    }
}
