package io.github.eunini.mrd.cases.config;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

/**
 * HTTP Basic with an in-memory user store.
 * <ul>
 *   <li>ROLE_INGEST (engine): POST /api/alerts only.</li>
 *   <li>ROLE_ANALYST: read and work cases.</li>
 *   <li>ROLE_SUPERVISOR: everything an analyst can do, plus filing approval and rejection.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = entryPoint();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/style.css", "/favicon.ico", "/demo-config.json")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/alerts").hasRole("INGEST")
                        .requestMatchers(HttpMethod.POST, "/api/cases/*/filing-approval", "/api/cases/*/filing-rejection")
                        .hasRole("SUPERVISOR")
                        .requestMatchers("/api/**").hasRole("ANALYST")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self' https://unpkg.com; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; frame-src 'self' blob:; object-src 'none'")));
        return http.build();
    }

    /**
     * Browser fetches from the UI send X-Requested-With so that a failed login returns a plain
     * 401 instead of the native Basic-auth dialog; other clients get the standard challenge.
     */
    private AuthenticationEntryPoint entryPoint() {
        BasicAuthenticationEntryPoint basic = new BasicAuthenticationEntryPoint();
        basic.setRealmName("mule-ring-detector");
        basic.afterPropertiesSet();
        return (request, response, authException) -> {
            if (request.getHeader("X-Requested-With") != null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\"}");
            } else {
                basic.commence(request, response, authException);
            }
        };
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_SUPERVISOR > ROLE_ANALYST");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties properties) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(properties.bcryptStrength());
        List<UserDetails> users = new ArrayList<>();
        for (SecurityProperties.User user : properties.users()) {
            String stored = user.password().startsWith("{")
                    ? user.password()
                    : "{bcrypt}" + bcrypt.encode(user.password());
            users.add(User.withUsername(user.username())
                    .password(stored)
                    .roles(user.roles().stream().map(r -> r.replaceFirst("^ROLE_", "")).toArray(String[]::new))
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
