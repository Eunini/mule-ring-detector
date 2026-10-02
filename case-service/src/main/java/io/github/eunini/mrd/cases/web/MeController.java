package io.github.eunini.mrd.cases.web;

import io.github.eunini.mrd.cases.config.UserDirectory;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity of the signed-in user, used by the UI to decide which actions to offer. */
@RestController
public class MeController {

    public record Me(String username, String fullName, List<String> roles) {
    }

    private final UserDirectory users;

    public MeController(UserDirectory users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public Me me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replaceFirst("^ROLE_", ""))
                .sorted()
                .toList();
        return new Me(authentication.getName(), users.profileOrDefault(authentication.getName()).fullName(), roles);
    }
}
