package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenValidationTest {
    @Test
    void cognitoRequiresAccessTokenForConfiguredClient() {
        var validator = SecurityConfiguration.clientValidator("closetos", "cognito");
        assertThat(validator.validate(token("access", "closetos")).hasErrors()).isFalse();
        assertThat(validator.validate(token("id", "closetos")).hasErrors()).isTrue();
        assertThat(validator.validate(token("access", "another-client")).hasErrors()).isTrue();
    }

    @Test
    void localOidcRequiresConfiguredAudience() {
        var validator = SecurityConfiguration.clientValidator("closetos", "oidc");
        assertThat(validator.validate(token("access", "closetos")).hasErrors()).isFalse();
        assertThat(validator.validate(token("access", "another-client")).hasErrors()).isTrue();
    }

    private Jwt token(String use, String client) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("token_use", use)
                .claim("client_id", client)
                .audience(List.of(client))
                .build();
    }
}
