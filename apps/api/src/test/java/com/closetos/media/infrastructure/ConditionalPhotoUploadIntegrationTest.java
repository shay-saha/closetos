package com.closetos.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.closetos.media.api.ImageRecord;
import com.closetos.media.api.ImageRole;
import com.closetos.media.api.ProcessingStatus;
import com.closetos.media.api.UploadInstructions;
import com.closetos.platform.api.MediaSigningAdmission;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers
class ConditionalPhotoUploadIntegrationTest {
    @Container
    static final GenericContainer<?> storage =
            new GenericContainer<>(
                            new ImageFromDockerfile()
                                    .withFileFromPath("Dockerfile", localStorageDockerfile()))
                    .withEnv("MINIO_ROOT_USER", "upload-test-access")
                    .withEnv("MINIO_ROOT_PASSWORD", "upload-test-secret")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static S3Client s3;
    private static S3Presigner presigner;
    private static HttpClient http;
    private final byte[] photograph =
            "photograph".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeAll
    static void clients() {
        var configuration = new StorageConfiguration();
        var credentials =
                configuration.storageCredentials(
                        "local", "upload-test-access", "upload-test-secret");
        String endpoint = "http://" + storage.getHost() + ":" + storage.getMappedPort(9000);
        s3 = configuration.s3Client(credentials, "eu-west-2", endpoint);
        presigner = configuration.s3Presigner(credentials, "eu-west-2", endpoint);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void closeClients() {
        if (http != null) http.close();
        if (presigner != null) presigner.close();
        if (s3 != null) s3.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedOriginalCannotBeOverwrittenOrRecreatedAfterConditionalErasure(boolean versioned)
            throws Exception {
        String bucket = bucket(versioned);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        var missingCondition = new HashMap<>(upload.headers());
        missingCondition.remove("If-None-Match");
        var unsigned = putResponse(upload, missingCondition);
        assertThat(unsigned.statusCode()).describedAs(unsigned.body()).isEqualTo(400);
        assertThat(unsigned.body()).contains("<Code>AccessDenied</Code>");
        var changedCondition = new HashMap<>(upload.headers());
        changedCondition.put("If-None-Match", "different-precondition");
        var altered = putResponse(upload, changedCondition);
        assertThat(altered.statusCode()).describedAs(altered.body()).isEqualTo(403);
        assertThat(put(upload, upload.headers())).isEqualTo(200);
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        assertThat(
                        s3.getObjectAsBytes(r -> r.bucket(bucket).key(image.sourceS3Key()))
                                .asByteArray())
                .isEqualTo(photograph);

        String etag = s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key())).eTag();
        s3.putObject(
                r ->
                        r.bucket(bucket)
                                .key(image.sourceS3Key())
                                .ifMatch(etag)
                                .metadata(Map.of("closetos-erased", "1")),
                RequestBody.empty());
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        assertThat(s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key())).contentLength())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyGuardRefusesAReservedUploadThatHasNotStarted(boolean versioned) throws Exception {
        String bucket = bucket(versioned);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        s3.putObject(
                r ->
                        r.bucket(bucket)
                                .key(image.sourceS3Key())
                                .ifNoneMatch("*")
                                .metadata(Map.of("closetos-erased", "1")),
                RequestBody.empty());
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        var head = s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key()));
        assertThat(head.contentLength()).isZero();
        assertThat(head.metadata()).containsEntry("closetos-erased", "1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Disabled", "Enabled", "Suspended"})
    void originalPrivacyErasurePurgesHistoryAndKeepsDerivativesAndNeighborKeys(String mode)
            throws Exception {
        String bucket = bucket(false);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        assertThat(put(upload, upload.headers())).isEqualTo(200);
        if (!"Disabled".equals(mode))
            s3.putBucketVersioning(
                    r -> r.bucket(bucket).versioningConfiguration(v -> v.status("Enabled")));
        var historical = new java.util.ArrayList<String>();
        for (int index = 0; index < 3; index++) {
            var head = s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key()));
            s3.putObject(
                    r -> r.bucket(bucket).key(image.sourceS3Key()).ifMatch(head.eTag()),
                    RequestBody.fromBytes(photograph));
            String version =
                    s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key())).versionId();
            if (version != null && !version.equals("null")) historical.add(version);
        }
        if ("Suspended".equals(mode))
            s3.putBucketVersioning(
                    r -> r.bucket(bucket).versioningConfiguration(v -> v.status("Suspended")));
        String card = image.prefix() + "pipelines/1-r1/card.webp";
        String neighbor = image.sourceS3Key() + ".backup";
        s3.putObject(r -> r.bucket(bucket).key(card), RequestBody.fromBytes(photograph));
        s3.putObject(r -> r.bucket(bucket).key(neighbor), RequestBody.fromBytes(photograph));
        uploads(bucket).delete(image.sourceS3Key());
        var guard = s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key()));
        assertThat(guard.contentLength()).isZero();
        assertThat(guard.metadata()).containsEntry("closetos-erased", "1");
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        var remaining = s3.listObjectVersions(r -> r.bucket(bucket).prefix(image.sourceS3Key()));
        assertThat(remaining.versions())
                .filteredOn(v -> v.key().equals(image.sourceS3Key()))
                .hasSize(1);
        assertThat(remaining.deleteMarkers()).isEmpty();
        for (String version : historical)
            assertThatThrownBy(
                            () ->
                                    s3.getObjectAsBytes(
                                            r ->
                                                    r.bucket(bucket)
                                                            .key(image.sourceS3Key())
                                                            .versionId(version)))
                    .isInstanceOfSatisfying(
                            S3Exception.class,
                            error -> assertThat(error.statusCode()).isEqualTo(404));
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(card)).asByteArray())
                .isEqualTo(photograph);
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(neighbor)).asByteArray())
                .isEqualTo(photograph);
        uploads(bucket).delete(image.sourceS3Key());
        assertThat(s3.headObject(r -> r.bucket(bucket).key(image.sourceS3Key())).versionId())
                .isEqualTo(guard.versionId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAnImageRemovesDerivativesAndVersionsAndFencesAllOriginalExtensions(
            boolean versioned) throws Exception {
        String bucket = bucket(versioned);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        assertThat(put(upload, upload.headers())).isEqualTo(200);
        String card = image.prefix() + "pipelines/1-r1/card.webp";
        for (int index = 0; index < 3; index++)
            s3.putObject(r -> r.bucket(bucket).key(card), RequestBody.fromBytes(photograph));
        s3.deleteObject(r -> r.bucket(bucket).key(card));
        uploads(bucket).deletePrefix(image.prefix());
        var remaining = s3.listObjectVersions(r -> r.bucket(bucket).prefix(image.prefix()));
        assertThat(remaining.versions()).hasSize(6).allMatch(v -> v.size() == 0 && v.isLatest());
        assertThat(remaining.deleteMarkers()).isEmpty();
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        uploads(bucket).deletePrefix(image.prefix());
        assertThat(s3.listObjectVersions(r -> r.bucket(bucket).prefix(image.prefix())).versions())
                .hasSize(6);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerErasureCoversUnstartedDeletedAndOrphanedPhotosAndPaginatedHistory(boolean versioned)
            throws Exception {
        String bucket = bucket(versioned);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        String missing = image.prefix() + "original.heic";
        String orphan = image.prefix() + "original.webp";
        String derivative = image.prefix() + "pipelines/1-r1/analysis.json";
        assertThat(put(upload, upload.headers())).isEqualTo(200);
        s3.deleteObject(r -> r.bucket(bucket).key(image.sourceS3Key()));
        s3.putObject(
                r -> r.bucket(bucket).key(orphan).ifNoneMatch("*"),
                RequestBody.fromBytes(photograph));
        String forgotten = image.prefix() + "original.jpg";
        var forgottenPhoto =
                s3.putObject(
                        r -> r.bucket(bucket).key(forgotten).ifNoneMatch("*"),
                        RequestBody.fromBytes(photograph));
        s3.deleteObject(r -> r.bucket(bucket).key(forgotten));
        if (versioned)
            s3.deleteObject(
                    r -> r.bucket(bucket).key(forgotten).versionId(forgottenPhoto.versionId()));
        for (int index = 0; index < (versioned ? 1005 : 3); index++)
            s3.putObject(r -> r.bucket(bucket).key(derivative), RequestBody.fromBytes(photograph));
        s3.deleteObject(r -> r.bucket(bucket).key(derivative));
        String other =
                image.sourceS3Key()
                        .replace(image.userId().toString(), UUID.randomUUID().toString());
        s3.putObject(
                r -> r.bucket(bucket).key(other).ifNoneMatch("*"),
                RequestBody.fromBytes(photograph));
        uploads(bucket).eraseOwner(image.userId(), java.util.List.of(image.sourceS3Key(), missing));
        var remaining =
                s3.listObjectVersions(
                        r -> r.bucket(bucket).prefix("users/" + image.userId() + "/"));
        assertThat(remaining.versions())
                .hasSize(versioned ? 4 : 3)
                .allMatch(v -> v.size() == 0 && v.isLatest());
        assertThat(remaining.deleteMarkers()).isEmpty();
        assertThat(put(upload, upload.headers())).isEqualTo(412);
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(other)).asByteArray())
                .isEqualTo(photograph);
        if (versioned)
            assertThatThrownBy(
                            () ->
                                    s3.putObject(
                                            r -> r.bucket(bucket).key(forgotten).ifNoneMatch("*"),
                                            RequestBody.fromBytes(photograph)))
                    .isInstanceOfSatisfying(
                            S3Exception.class,
                            error -> assertThat(error.statusCode()).isEqualTo(412));
    }

    @Test
    void anAlreadyStreamingUploadMustEitherLoseToTheGuardOrBeErasedBeforeAcknowledgement()
            throws Exception {
        String bucket = bucket(true);
        var image = image();
        var upload = uploads(bucket).signUpload(image);
        URI target = URI.create(upload.url());
        var credentials =
                new StorageConfiguration()
                        .storageCredentials("local", "upload-test-access", "upload-test-secret");
        try (var bounded =
                        S3Client.builder()
                                .region(software.amazon.awssdk.regions.Region.EU_WEST_2)
                                .credentialsProvider(credentials)
                                .endpointOverride(URI.create("http://" + target.getAuthority()))
                                .serviceConfiguration(
                                        software.amazon.awssdk.services.s3.S3Configuration.builder()
                                                .pathStyleAccessEnabled(true)
                                                .build())
                                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(2)))
                                .build();
                var socket = new java.net.Socket(target.getHost(), target.getPort());
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            socket.setSoTimeout(10_000);
            var output = socket.getOutputStream();
            var headers =
                    new StringBuilder(
                            "PUT "
                                    + target.getRawPath()
                                    + "?"
                                    + target.getRawQuery()
                                    + " HTTP/1.1\r\nHost: "
                                    + target.getAuthority()
                                    + "\r\nContent-Length: "
                                    + photograph.length
                                    + "\r\nConnection: close\r\n");
            upload.headers()
                    .forEach(
                            (name, value) ->
                                    headers.append(name).append(": ").append(value).append("\r\n"));
            output.write((headers + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.write(photograph, 0, 1);
            output.flush();
            var cleanup =
                    executor.submit(
                            () -> {
                                try {
                                    new S3PhotoErasure(bounded, bucket)
                                            .original(image.sourceS3Key());
                                    return true;
                                } catch (com.closetos.media.api.MediaErasurePending pending) {
                                    return false;
                                }
                            });
            boolean acknowledged;
            try {
                acknowledged = cleanup.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                output.write(photograph, 1, photograph.length - 1);
                output.flush();
            }
            var input =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    socket.getInputStream(),
                                    java.nio.charset.StandardCharsets.US_ASCII));
            String status = input.readLine();
            assertThat(status).containsAnyOf(" 200 ", " 412 ");
            if (acknowledged) assertThat(status).contains(" 412 ");
            uploads(bucket).delete(image.sourceS3Key());
            var remaining =
                    s3.listObjectVersions(r -> r.bucket(bucket).prefix(image.sourceS3Key()));
            assertThat(remaining.versions())
                    .hasSize(1)
                    .allMatch(v -> v.size() == 0 && v.isLatest());
            assertThat(remaining.deleteMarkers()).isEmpty();
            assertThat(put(upload, upload.headers())).isEqualTo(412);
        }
    }

    private int put(UploadInstructions upload, Map<String, String> headers) throws Exception {
        return putResponse(upload, headers).statusCode();
    }

    private HttpResponse<String> putResponse(UploadInstructions upload, Map<String, String> headers)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(upload.url()))
                        .timeout(Duration.ofSeconds(10))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(photograph));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String bucket(boolean versioned) {
        String name = "upload-test-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(name));
        if (versioned)
            s3.putBucketVersioning(
                    r -> r.bucket(name).versioningConfiguration(v -> v.status("Enabled")));
        return name;
    }

    private S3ObjectStorage uploads(String bucket) {
        var admission = mock(MediaSigningAdmission.class);
        doAnswer(call -> call.<Supplier<?>>getArgument(1).get()).when(admission).sign(any(), any());
        return new S3ObjectStorage(
                s3,
                presigner,
                mock(MediaDownloadSigner.class),
                Clock.systemUTC(),
                bucket,
                admission);
    }

    private ImageRecord image() throws Exception {
        UUID owner = UUID.randomUUID(), garment = UUID.randomUUID(), image = UUID.randomUUID();
        return new ImageRecord(
                image,
                garment,
                UUID.randomUUID(),
                owner,
                "users/" + owner + "/garments/" + garment + "/images/" + image + "/original.png",
                "image/png",
                photograph.length,
                Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(photograph)),
                ProcessingStatus.AWAITING_UPLOAD,
                null,
                false,
                "photo.png",
                ImageRole.FRONT);
    }

    private static Path localStorageDockerfile() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path dockerfile = path.resolve("infra/local/Minio.Dockerfile");
            if (Files.isRegularFile(dockerfile)) return dockerfile;
        }
        throw new IllegalStateException("Local storage Dockerfile is missing");
    }
}
