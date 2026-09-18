package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTrustBoundaryTest {
    private KeyPair trustedKey;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        trustedKey = newKey();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) trustedKey.getPublic()).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer("https://issuer.test"),
                        SecurityConfiguration.clientValidator("closetos", "cognito")));
    }

    @Test
    void validAccessTokenIsAccepted() {
        String token =
                sign(
                        trustedKey,
                        "https://issuer.test",
                        "access",
                        "closetos",
                        Instant.now().plusSeconds(300));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("user-a");
    }

    @Test
    void forgedSignatureIsRejected() throws Exception {
        String token =
                sign(
                        newKey(),
                        "https://issuer.test",
                        "access",
                        "closetos",
                        Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String token =
                sign(
                        trustedKey,
                        "https://issuer.test",
                        "access",
                        "closetos",
                        Instant.now().minusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongIssuerIdTokenAndWrongClientAreRejected() {
        for (String token :
                new String[] {
                    sign(
                            trustedKey,
                            "https://untrusted.test",
                            "access",
                            "closetos",
                            Instant.now().plusSeconds(300)),
                    sign(
                            trustedKey,
                            "https://issuer.test",
                            "id",
                            "closetos",
                            Instant.now().plusSeconds(300)),
                    sign(
                            trustedKey,
                            "https://issuer.test",
                            "access",
                            "other-client",
                            Instant.now().plusSeconds(300))
                }) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
    }

    private String sign(
            KeyPair key, String issuer, String tokenUse, String client, Instant expiresAt) {
        RSAKey rsa =
                new RSAKey.Builder((RSAPublicKey) key.getPublic())
                        .privateKey((RSAPrivateKey) key.getPrivate())
                        .keyID(UUID.randomUUID().toString())
                        .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject("user-a")
                        .issuedAt(Instant.now().minusSeconds(600))
                        .expiresAt(expiresAt)
                        .claim("token_use", tokenUse)
                        .claim("client_id", client)
                        .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private KeyPair newKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
