package com.monjaro.auth;

import com.monjaro.common.ApiException;
import com.monjaro.users.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@SecurityRequirements
public class AuthController {
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,40}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+", message = "must use printable ASCII characters") String password) {}
    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}
    private record Credentials(UUID id, String passwordHash) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final UserStore users;
    private final Duration ttl;
    private final String issuer;
    private final String dummyHash;

    public AuthController(JdbcClient jdbc, PasswordEncoder passwords, JwtEncoder encoder, UserStore users,
                          @Value("${app.jwt.ttl}") Duration ttl, @Value("${app.jwt.issuer}") String issuer) {
        this.jdbc = jdbc; this.passwords = passwords; this.encoder = encoder; this.users = users;
        this.ttl = ttl; this.issuer = issuer;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, ?)")
                .params(id, request.username().toLowerCase(Locale.ROOT), normalize(request.email()), passwords.encode(request.password())).update();
        return token(id);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        var credentials = jdbc.sql("SELECT id, password_hash FROM users WHERE email = ?")
                .param(normalize(request.email()))
                .query((row, index) -> new Credentials(row.getObject("id", UUID.class), row.getString("password_hash"))).optional();
        boolean valid = passwords.matches(request.password(), credentials.map(Credentials::passwordHash).orElse(dummyHash));
        if (!valid || credentials.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        return token(credentials.get().id());
    }

    private TokenResponse token(UUID id) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(id.toString())
                .issuedAt(now).expiresAt(now.plus(ttl)).build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(value, "Bearer", ttl.toSeconds(), users.get(id));
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
}
