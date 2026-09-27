package com.closetos.media.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowJobTest {
    private static final UUID IMAGE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GARMENT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String PREFIX =
            "users/00000000-0000-4000-8000-000000000003/garments/"
                    + GARMENT
                    + "/images/"
                    + IMAGE
                    + "/";

    @Test
    void deletionScopeIncludesOnlyThePhotographOwningTheProcessingJob() {
        assertThat(job(PREFIX + "original.png", PREFIX + "pipelines/1-r1/").imagePrefix())
                .isEqualTo(PREFIX);
        for (String source :
                new String[] {
                    "original.png",
                    "",
                    PREFIX.replace("/images/", "/images/../") + "original.png",
                    PREFIX.replace(IMAGE.toString(), UUID.randomUUID().toString()) + "original.png",
                    PREFIX.replace(GARMENT.toString(), UUID.randomUUID().toString())
                            + "original.png",
                    PREFIX + "card.webp",
                    PREFIX + "original.png/../"
                }) {
            assertThatThrownBy(() -> job(source, PREFIX + "pipelines/1-r1/").imagePrefix())
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> job(PREFIX + "original.png", "users/other/").imagePrefix())
                .isInstanceOf(IllegalStateException.class);
    }

    private WorkflowJob job(String source, String output) {
        return new WorkflowJob(
                UUID.randomUUID(),
                IMAGE,
                GARMENT,
                UUID.randomUUID(),
                source,
                output,
                "image/png",
                10,
                "checksum",
                "1-r1",
                "request");
    }
}
