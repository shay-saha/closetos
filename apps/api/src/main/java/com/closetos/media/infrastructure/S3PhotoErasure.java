package com.closetos.media.infrastructure;

import com.closetos.media.api.MediaErasurePending;
import com.closetos.platform.api.DomainException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3PhotoErasure {
    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final String IMAGE_PREFIX =
            "users/" + UUID_PATTERN + "/garments/" + UUID_PATTERN + "/images/" + UUID_PATTERN + "/";
    private static final Pattern ORIGINAL =
            Pattern.compile(IMAGE_PREFIX + "original\\.(jpg|jpeg|png|webp|heic|heif)");
    private static final Map<String, String> ERASED = Map.of("closetos-erased", "1");
    private final S3Client s3;
    private final String bucket;

    S3PhotoErasure(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    void original(String key) {
        validateOriginal(key);
        confirmed(() -> erase(key, key::equals, List.of(key)));
    }

    void image(String prefix) {
        if (prefix == null || !prefix.matches(IMAGE_PREFIX))
            throw DomainException.invalid("Invalid media prefix.");
        var originals =
                List.of("jpg", "jpeg", "png", "webp", "heic", "heif").stream()
                        .map(extension -> prefix + "original." + extension)
                        .toList();
        confirmed(() -> erase(prefix, key -> true, originals));
    }

    void owner(UUID owner, Collection<String> originals) {
        if (owner == null || originals == null)
            throw DomainException.invalid("Invalid photo cleanup scope.");
        String prefix = "users/" + owner + "/";
        for (String key : originals) {
            validateOriginal(key);
            if (!key.startsWith(prefix))
                throw DomainException.invalid("Photo cleanup sources must belong to this account.");
        }
        confirmed(() -> erase(prefix, key -> true, originals));
    }

    private void erase(String prefix, Predicate<String> included, Collection<String> originals) {
        Map<String, String> guards = new HashMap<>();
        for (String key : originals) guards.put(key, guard(key));
        for (var page : s3.listObjectVersionsPaginator(listing(prefix))) {
            validatePage(prefix, page);
            for (var version : page.versions()) {
                String key = version.key();
                if (included.test(key)
                        && ORIGINAL.matcher(key).matches()
                        && !guards.containsKey(key)) guards.put(key, guard(key));
            }
            for (var marker : page.deleteMarkers()) {
                String key = marker.key();
                if (included.test(key)
                        && ORIGINAL.matcher(key).matches()
                        && !guards.containsKey(key)) guards.put(key, guard(key));
            }
        }
        purgePages(prefix, included, guards);
        for (var page : s3.listObjectVersionsPaginator(listing(prefix))) {
            validatePage(prefix, page);
            for (var version : page.versions()) {
                if (included.test(version.key())
                        && (!version.versionId().equals(guards.get(version.key()))
                                || !Boolean.TRUE.equals(version.isLatest())
                                || version.size() == null
                                || version.size() != 0)) throw new MediaErasurePending();
            }
            if (page.deleteMarkers().stream().anyMatch(marker -> included.test(marker.key())))
                throw new MediaErasurePending();
        }
        for (var entry : guards.entrySet()) {
            var head = head(entry.getKey()).orElseThrow(MediaErasurePending::new);
            if (!erased(head) || !entry.getValue().equals(versionId(head)))
                throw new MediaErasurePending();
        }
    }

    private void purgePages(String prefix, Predicate<String> included, Map<String, String> guards) {
        var pages = s3.listObjectVersionsPaginator(listing(prefix)).iterator();
        ListObjectVersionsResponse pending = null;
        while (pages.hasNext()) {
            var next = pages.next();
            // Fetch the following page before deleting the version used as its pagination marker.
            if (pending != null) purgePage(prefix, pending, included, guards);
            pending = next;
        }
        if (pending != null) purgePage(prefix, pending, included, guards);
    }

    private void purgePage(
            String prefix,
            ListObjectVersionsResponse page,
            Predicate<String> included,
            Map<String, String> guards) {
        validatePage(prefix, page);
        var deletions = new ArrayList<ObjectIdentifier>();
        for (var version : page.versions()) {
            if (!included.test(version.key())) continue;
            String guard = guards.get(version.key());
            if (guard != null
                    && (version.isLatest() == null
                            || (version.isLatest() && !version.versionId().equals(guard))))
                throw new MediaErasurePending();
            if (version.versionId().equals(guard)) {
                if (!Boolean.TRUE.equals(version.isLatest())
                        || version.size() == null
                        || version.size() != 0) throw new MediaErasurePending();
            } else deletions.add(identifier(version.key(), version.versionId()));
        }
        for (var marker : page.deleteMarkers())
            if (included.test(marker.key()))
                deletions.add(identifier(marker.key(), marker.versionId()));
        delete(deletions);
    }

    private String guard(String key) {
        var current = head(key);
        if (current.isPresent() && erased(current.get())) return versionId(current.get());
        var request =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .metadata(ERASED);
        if (current.isEmpty()) request.ifNoneMatch("*");
        else {
            String etag = current.get().eTag();
            if (etag == null || etag.isBlank()) throw new MediaErasurePending();
            request.ifMatch(etag);
        }
        var result = s3.putObject(request.build(), RequestBody.empty());
        var stored = head(key).orElseThrow(MediaErasurePending::new);
        if (!erased(stored) || !normalizeVersion(result.versionId()).equals(versionId(stored)))
            throw new MediaErasurePending();
        return versionId(stored);
    }

    private Optional<HeadObjectResponse> head(String key) {
        try {
            return Optional.of(
                    s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return Optional.empty();
            throw exception;
        }
    }

    private void delete(List<ObjectIdentifier> objects) {
        for (int start = 0; start < objects.size(); start += 1000) {
            var batch = objects.subList(start, Math.min(start + 1000, objects.size()));
            var result =
                    s3.deleteObjects(
                            DeleteObjectsRequest.builder()
                                    .bucket(bucket)
                                    .delete(deletion -> deletion.objects(batch).quiet(true))
                                    .build());
            if (!result.errors().isEmpty()) throw new MediaErasurePending();
        }
    }

    private ListObjectVersionsRequest listing(String prefix) {
        return ListObjectVersionsRequest.builder()
                .bucket(bucket)
                .prefix(prefix)
                .maxKeys(1000)
                .build();
    }

    private void validatePage(String prefix, ListObjectVersionsResponse page) {
        for (ObjectVersion version : page.versions())
            validateEntry(prefix, version.key(), version.versionId());
        for (DeleteMarkerEntry marker : page.deleteMarkers())
            validateEntry(prefix, marker.key(), marker.versionId());
    }

    private void validateEntry(String prefix, String key, String version) {
        if (key == null || !key.startsWith(prefix) || version == null || version.isBlank())
            throw new MediaErasurePending();
    }

    private static ObjectIdentifier identifier(String key, String version) {
        return ObjectIdentifier.builder().key(key).versionId(version).build();
    }

    private static boolean erased(HeadObjectResponse head) {
        return head.contentLength() != null
                && head.contentLength() == 0
                && "1".equals(head.metadata().get("closetos-erased"));
    }

    private static String versionId(HeadObjectResponse head) {
        return normalizeVersion(head.versionId());
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "null";
        if (version.isBlank()) throw new MediaErasurePending();
        return version;
    }

    private static void validateOriginal(String key) {
        if (key == null || !ORIGINAL.matcher(key).matches())
            throw DomainException.invalid("Invalid original photograph key.");
    }

    private static void confirmed(Runnable operation) {
        try {
            operation.run();
        } catch (SdkException exception) {
            throw new MediaErasurePending();
        }
    }
}
