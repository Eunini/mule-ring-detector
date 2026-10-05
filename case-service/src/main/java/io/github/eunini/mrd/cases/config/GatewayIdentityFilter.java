package io.github.eunini.mrd.cases.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates workspace identities signed by the private application gateway. */
public final class GatewayIdentityFilter extends OncePerRequestFilter {
    private final String secret;
    private final ObjectMapper mapper;
    private final UserDirectory users;

    public GatewayIdentityFilter(String secret, ObjectMapper mapper, UserDirectory users) {
        this.secret = secret;
        this.mapper = mapper;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String encoded = request.getHeader("X-Workspace-Identity");
        if (encoded == null || secret.isBlank()) { chain.doFilter(request, response); return; }
        try {
            String signature = request.getHeader("X-Workspace-Signature");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8));
            if (signature == null || !MessageDigest.isEqual(expected, java.util.HexFormat.of().parseHex(signature))) {
                response.sendError(401); return;
            }
            JsonNode identity = mapper.readTree(Base64.getUrlDecoder().decode(encoded));
            if (Math.abs(Instant.now().getEpochSecond() - identity.path("time").asLong()) > 60) {
                response.sendError(401); return;
            }
            String actor = identity.path("actor").asText();
            String role = identity.path("role").asText();
            if (!actor.matches("u_[a-f0-9-]{36}") || !(role.equals("ANALYST") || role.equals("SUPERVISOR"))) {
                response.sendError(401); return;
            }
            for (JsonNode member : identity.path("members")) {
                String name = member.path("actor").asText();
                String memberRole = member.path("role").asText();
                if (name.matches("u_[a-f0-9-]{36}") && (memberRole.equals("ANALYST") || memberRole.equals("SUPERVISOR"))) {
                    users.register(new UserDirectory.UserProfile(name, member.path("name").asText(),
                            member.path("email").asText(null), List.of(memberRole)));
                }
            }
            List<SimpleGrantedAuthority> authorities = role.equals("SUPERVISOR")
                    ? List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"), new SimpleGrantedAuthority("ROLE_ANALYST"))
                    : List.of(new SimpleGrantedAuthority("ROLE_ANALYST"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(actor, null, authorities));
        } catch (Exception invalid) {
            response.sendError(401);
            return;
        }
        chain.doFilter(request, response);
    }
}
