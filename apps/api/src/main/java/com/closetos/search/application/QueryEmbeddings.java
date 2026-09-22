package com.closetos.search.application;

import com.closetos.search.api.EmbeddingProviderPort;
import com.closetos.search.api.EmbeddingProviderPort.Input;
import com.closetos.search.api.EmbeddingProviderPort.ModelInfo;
import com.closetos.search.api.EmbeddingProviderPort.VectorResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QueryEmbeddings {
    private final EmbeddingProviderPort provider;
    private final JsonMapper json;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(32, .75f, true);

    public QueryEmbeddings(EmbeddingProviderPort provider, JsonMapper json, Clock clock) {
        this.provider = provider;
        this.json = json;
        this.clock = clock;
    }

    public VectorResult embed(UUID wardrobe, Input input, ModelInfo model) {
        String key = hash(wardrobe + ":" + model.modelKey() + ":" + json.writeValueAsString(input));
        Entry entry;
        boolean generate;
        synchronized (cache) {
            cache.values().removeIf(value -> value.until().isBefore(clock.instant()));
            entry = cache.get(key);
            generate = entry == null;
            if (generate) {
                entry = new Entry(clock.instant().plusSeconds(600), new CompletableFuture<>());
                cache.put(key, entry);
                while (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
            }
        }
        if (generate) {
            try {
                entry.result().complete(provider.embed(input, model));
            } catch (RuntimeException exception) {
                entry.result().completeExceptionally(exception);
                synchronized (cache) {
                    cache.remove(key, entry);
                }
            }
        }
        try {
            return entry.result().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw exception;
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Entry(Instant until, CompletableFuture<VectorResult> result) {}
}
