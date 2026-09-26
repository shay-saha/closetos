package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudFrontDownloadSignerTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String DOMAIN = "d111example.cloudfront.net";
    private static final String KEY_ID = "KEXAMPLE123";
    private static final String PREFIX =
            "users/00000000-0000-4000-8000-000000000001/garments/00000000-0000-4000-8000-000000000002/images/00000000-0000-4000-8000-000000000003/";
    private static KeyPair signingKey;

    @BeforeAll
    static void signingKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
    }

    @ParameterizedTest
    @ValueSource(strings = {"isolated", "display", "card", "thumbnail"})
    void signatureBindsOneDerivativeAndExpiryAndUsesSha256(String derivative) throws Exception {
        String key = PREFIX + "pipelines/1-r2/" + derivative + ".webp";
        Instant expiry = NOW.plusSeconds(900);
        URI url = URI.create(signer().sign(key, expiry));
        Map<String, String> query = query(url);
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).isEqualTo(DOMAIN);
        assertThat(url.getPath()).isEqualTo("/" + key);
        assertThat(query)
                .containsOnlyKeys("Expires", "Signature", "Key-Pair-Id", "Hash-Algorithm")
                .containsEntry("Expires", Long.toString(expiry.getEpochSecond()))
                .containsEntry("Key-Pair-Id", KEY_ID)
                .containsEntry("Hash-Algorithm", "SHA256");
        byte[] signature =
                Base64.getDecoder()
                        .decode(
                                query.get("Signature")
                                        .replace('-', '+')
                                        .replace('_', '=')
                                        .replace('~', '/'));
        assertThat(verifies("https://" + DOMAIN + "/" + key, expiry, signature)).isTrue();
        assertThat(
                        verifies(
                                "https://" + DOMAIN + "/" + key.replace("1-r2", "1-r3"),
                                expiry,
                                signature))
                .isFalse();
        assertThat(verifies("https://" + DOMAIN + "/" + key, expiry.plusSeconds(1), signature))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "original.jpg",
                "original.webp",
                "pipelines/1-r2/manifest.json",
                "pipelines/1-r2/analysis.json",
                "pipelines/1-r2/../original.webp",
                "pipelines/1-r2/display.webp?extra=1",
                "pipelines/1-r2/display.webp#fragment",
                "pipelines/1-r2/%2e%2e/display.webp",
                "pipelines/1-r2/not-a-derivative.webp",
                "pipelines/1-r6/display.webp",
                "pipelines/1-r2/display.png"
            })
    void originalsManifestsTraversalAndUnknownDerivativesCannotBeSigned(String suffix) {
        assertThatThrownBy(() -> signer().sign(PREFIX + suffix, NOW.plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unscopedAndMalformedOwnerKeysCannotBeSigned() {
        for (String key :
                new String[] {
                    "display.webp",
                    "/" + PREFIX + "pipelines/1-r2/display.webp",
                    PREFIX.replace("users/", "public/") + "pipelines/1-r2/display.webp",
                    PREFIX.replace("00000000-0000-4000-8000-000000000001", "another-user")
                            + "pipelines/1-r2/display.webp"
                })
            assertThatThrownBy(() -> signer().sign(key, NOW.plusSeconds(600)))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredAndExcessiveLifetimesCannotBeSigned() {
        for (Instant expiry :
                new Instant[] {
                    NOW.minusSeconds(1), NOW, NOW.plusMillis(500), NOW.plusSeconds(901), null
                })
            assertThatThrownBy(() -> signer().sign(PREFIX + "pipelines/1-r2/display.webp", expiry))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConfigurationFailsWithoutExposingKeyMaterial() throws Exception {
        for (String domain :
                new String[] {
                    "", "https://" + DOMAIN, DOMAIN + "/path", "media.example.test", DOMAIN + ":443"
                })
            assertThatThrownBy(
                            () ->
                                    new CloudFrontDownloadSigner(
                                            CLOCK, domain, KEY_ID, pem(signingKey)))
                    .isInstanceOf(IllegalStateException.class);
        for (String keyId : new String[] {"", "KKEY&Signature=bad", "lowercase"})
            assertThatThrownBy(
                            () ->
                                    new CloudFrontDownloadSigner(
                                            CLOCK, DOMAIN, keyId, pem(signingKey)))
                    .isInstanceOf(IllegalStateException.class);
        for (String pem :
                new String[] {
                    "secret-key-content",
                    "-----BEGIN PRIVATE KEY-----\nsecret-key-content\n-----END PRIVATE KEY-----",
                    pem(signingKey).replace("PRIVATE KEY", "PUBLIC KEY"),
                    "x".repeat(17 * 1024)
                })
            assertThatThrownBy(() -> new CloudFrontDownloadSigner(CLOCK, DOMAIN, KEY_ID, pem))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CloudFront signing requires an RSA-2048 PKCS#8 private key.")
                    .hasNoCause();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String weakKey = pem(generator.generateKeyPair());
        assertThatThrownBy(() -> new CloudFrontDownloadSigner(CLOCK, DOMAIN, KEY_ID, weakKey))
                .isInstanceOf(IllegalStateException.class);
    }

    private CloudFrontDownloadSigner signer() {
        return new CloudFrontDownloadSigner(CLOCK, DOMAIN, KEY_ID, pem(signingKey));
    }

    private static boolean verifies(String resource, Instant expiry, byte[] signature)
            throws Exception {
        String policy =
                "{\"Statement\":[{\"Resource\":\""
                        + resource
                        + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                        + expiry.getEpochSecond()
                        + "}}}]}";
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(signingKey.getPublic());
        verifier.update(policy.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signature);
    }

    static String pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(
                        Collectors.toMap(
                                part -> part[0],
                                part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
    }
}
