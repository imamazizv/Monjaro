package com.monjaro.users;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserStore users;
    public UserController(UserStore users) { this.users = users; }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.get(UUID.fromString(jwt.getSubject()));
    }
}
