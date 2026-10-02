package io.github.eunini.mrd.cases.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * In-memory user store. Passwords are plain values from configuration or environment
 * (hashed with BCrypt at startup) or pre-encoded values such as "{bcrypt}...".
 */
@Validated
@ConfigurationProperties("mrd.security")
public record SecurityProperties(
        @Min(4) @Max(16) Integer bcryptStrength,
        @NotEmpty List<@Valid User> users) {

    public SecurityProperties {
        bcryptStrength = bcryptStrength == null ? 10 : bcryptStrength;
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record User(
            @NotBlank String username,
            @NotBlank String password,
            String fullName,
            String email,
            @NotEmpty List<@NotBlank String> roles) {

        @Override
        public String toString() {
            return "User[username=" + username + ", roles=" + roles + "]";
        }
    }
}
