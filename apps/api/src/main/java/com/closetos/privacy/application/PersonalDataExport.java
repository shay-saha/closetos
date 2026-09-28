package com.closetos.privacy.application;

import com.closetos.identity.api.IdentityAccess;
import com.closetos.platform.api.DomainException;
import com.closetos.platform.api.ExpensiveAction;
import com.closetos.platform.api.ExpensiveActionLimits;
import com.closetos.platform.api.PersonalDataContributor;
import com.closetos.platform.api.PersonalDataWriter;
import com.closetos.wardrobe.api.WardrobeAccess;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PersonalDataExport {
    private final IdentityAccess identity;
    private final WardrobeAccess wardrobes;
    private final ExpensiveActionLimits limits;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final List<PersonalDataContributor> contributors;

    public PersonalDataExport(
            IdentityAccess identity,
            WardrobeAccess wardrobes,
            ExpensiveActionLimits limits,
            JdbcTemplate jdbc,
            JsonMapper json,
            Clock clock,
            MeterRegistry metrics,
            List<PersonalDataContributor> contributors) {
        this.identity = identity;
        this.wardrobes = wardrobes;
        this.limits = limits;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.contributors =
                contributors.stream()
                        .sorted(Comparator.comparing(part -> part.getClass().getName()))
                        .toList();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void download(HttpServletResponse response) throws IOException {
        var owner = identity.currentUserId();
        var wardrobe = wardrobes.currentWardrobeId();
        Boolean acquired =
                jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(CAST(? AS text), 937146))",
                        Boolean.class,
                        wardrobe);
        if (!Boolean.TRUE.equals(acquired))
            throw new DomainException(
                    429,
                    "DATA_EXPORT_BUSY",
                    "A data download is already being prepared. Wait for it to finish before trying again.");
        limits.consume(wardrobe, ExpensiveAction.DATA_EXPORT);
        var now = clock.instant();
        var photoExpiry = now.plus(Duration.ofMinutes(15));
        response.setContentType("application/json");
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"closetos-data-"
                        + now.atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                        + ".json\"");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (var output = json.createGenerator(response.getOutputStream())) {
            output.writeStartObject();
            output.writeNumberProperty("schemaVersion", 1);
            output.writeStringProperty("exportedAt", now.toString());
            output.writeStringProperty("photoLinksExpireAt", photoExpiry.toString());
            var writer = new PersonalDataWriter(output, jdbc, json);
            for (var contributor : contributors)
                contributor.write(writer, owner, wardrobe, photoExpiry);
            // The client only offers the file when the entire snapshot reached this marker.
            output.writeBooleanProperty("complete", true);
            output.writeEndObject();
            output.flush();
            metrics.counter("privacy.data.exports.completed").increment();
            metrics.summary("privacy.data.exports.rows").record(writer.rowCount());
        }
    }
}
