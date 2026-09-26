package com.closetos.media.infrastructure;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import software.amazon.awssdk.services.cloudfront.utils.SigningHashAlgorithm;

final class CloudFrontDownloadSigner implements MediaDownloadSigner {
    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DERIVATIVE =
            Pattern.compile(
                    "users/"
                            + UUID
                            + "/garments/"
                            + UUID
                            + "/images/"
                            + UUID
                            + "/pipelines/[0-9]+-r[1-5]/(isolated|display|card|thumbnail)\\.webp");
    private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
    private final CloudFrontUtilities utilities = CloudFrontUtilities.create();
    private final Clock clock;
    private final String origin;
    private final String keyId;
    private final RSAPrivateKey privateKey;

    CloudFrontDownloadSigner(Clock clock, String domain, String keyId, String privateKeyPem) {
        if (domain == null || !domain.matches("[a-z0-9]+\\.cloudfront\\.net"))
            throw new IllegalStateException(
                    "Provide the CloudFront distribution domain without a scheme or path.");
        if (keyId == null || !keyId.matches("[A-Z0-9]{1,64}"))
            throw new IllegalStateException("Provide the CloudFront public signing key ID.");
        this.clock = clock;
        this.origin = "https://" + domain;
        this.keyId = keyId;
        this.privateKey = parsePrivateKey(privateKeyPem);
    }

    @Override
    public String sign(String key, Instant expiresAt) {
        if (key == null || !DERIVATIVE.matcher(key).matches())
            throw new IllegalArgumentException(
                    "Only versioned wardrobe derivatives may be signed for delivery.");
        Instant now = clock.instant();
        if (expiresAt == null
                || expiresAt.getEpochSecond() <= now.getEpochSecond()
                || expiresAt.isAfter(now.plus(MAXIMUM_LIFETIME)))
            throw new IllegalArgumentException("Media access must expire within fifteen minutes.");
        return utilities
                .getSignedUrlWithCannedPolicy(
                        CannedSignerRequest.builder()
                                .resourceUrl(origin + "/" + key)
                                .keyPairId(keyId)
                                .privateKey(privateKey)
                                .expirationDate(expiresAt)
                                .hashAlgorithm(SigningHashAlgorithm.SHA256)
                                .build())
                .url();
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        try {
            if (pem == null || pem.length() > 16 * 1024) throw new IllegalArgumentException();
            String trimmed = pem.trim();
            if (!trimmed.startsWith("-----BEGIN PRIVATE KEY-----")
                    || !trimmed.endsWith("-----END PRIVATE KEY-----"))
                throw new IllegalArgumentException();
            byte[] encoded =
                    Base64.getDecoder()
                            .decode(
                                    trimmed.substring(
                                                    "-----BEGIN PRIVATE KEY-----".length(),
                                                    trimmed.length()
                                                            - "-----END PRIVATE KEY-----".length())
                                            .replaceAll("\\s", ""));
            var key =
                    (RSAPrivateKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (key.getModulus().bitLength() != 2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "CloudFront signing requires an RSA-2048 PKCS#8 private key.");
        }
    }
}
