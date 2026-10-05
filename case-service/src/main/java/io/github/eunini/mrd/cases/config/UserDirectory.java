package io.github.eunini.mrd.cases.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Read-only profile lookup (display name, e-mail, roles) for configured users. */
@Component
public class UserDirectory {

    public record UserProfile(String username, String fullName, String email, List<String> roles) {

        public boolean hasRole(String role) {
            return roles.contains(role);
        }

        public boolean canWorkCases() {
            return hasRole("ANALYST") || hasRole("SUPERVISOR");
        }
    }

    private final Map<String, UserProfile> profiles = new java.util.concurrent.ConcurrentHashMap<>();

    public UserDirectory(SecurityProperties properties) {
        for (SecurityProperties.User user : properties.users()) {
            List<String> roles = user.roles().stream().map(r -> r.replaceFirst("^ROLE_", "")).toList();
            String fullName = user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName();
            profiles.put(user.username(), new UserProfile(user.username(), fullName, user.email(), roles));
        }
    }

    public void register(UserProfile profile) { profiles.put(profile.username(), profile); }

    public Optional<UserProfile> find(String username) {
        return Optional.ofNullable(profiles.get(username));
    }

    public UserProfile profileOrDefault(String username) {
        return find(username).orElseGet(() -> new UserProfile(username, username, null, List.of()));
    }
}
