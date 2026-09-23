package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class AdministratorAuthoritiesTest {
    @Test
    void cognitoUsesOnlyItsExactAdministratorGroupAndPreservesScopes() {
        var converter = new AdministratorAuthorities("cognito");
        assertThat(
                        converter.convert(
                                token(
                                        Map.of(
                                                "cognito:groups",
                                                List.of("closetos-admin"),
                                                "scope",
                                                "wardrobe.read"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_wardrobe.read", "ROLE_CLOSETOS_ADMIN");
        for (Object roles :
                List.of(
                        "closetos-admin",
                        List.of("administrator"),
                        List.of("closetos-admin-extra"),
                        Map.of("closetos-admin", true)))
            assertThat(converter.convert(token(Map.of("cognito:groups", roles)))).isEmpty();
        assertThat(
                        converter.convert(
                                token(
                                        Map.of(
                                                "realm_access",
                                                Map.of("roles", List.of("closetos-admin"))))))
                .isEmpty();
    }

    @Test
    void localOidcRequiresTheRealmRoleAndIgnoresClientRolesAndCognitoClaims() {
        var converter = new AdministratorAuthorities("oidc");
        assertThat(
                        converter.convert(
                                token(
                                        Map.of(
                                                "realm_access",
                                                Map.of("roles", List.of("closetos-admin"))))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CLOSETOS_ADMIN");
        for (var claims :
                List.of(
                        Map.of("cognito:groups", List.of("closetos-admin")),
                        Map.of("resource_access", Map.of("roles", List.of("closetos-admin"))),
                        Map.of("realm_access", Map.of("roles", "closetos-admin")),
                        Map.of("realm_access", "closetos-admin")))
            assertThat(converter.convert(token(claims))).isEmpty();
    }

    private Jwt token(Map<String, ?> claims) {
        return Jwt.withTokenValue("validated-token")
                .header("alg", "RS256")
                .subject("administrator")
                .claims(values -> values.putAll(claims))
                .build();
    }
}
