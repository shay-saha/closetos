package com.closetos;

import static org.assertj.core.api.Assertions.assertThat;

import com.closetos.garment.infrastructure.SmartQueryCompiler;
import com.closetos.search.application.SearchConstraints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchConstraintsTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    final SearchConstraints parser = new SearchConstraints(JsonMapper.builder().build(), clock);

    @Test
    void categoryAlternativesAndNegatedCompoundWordsKeepTheirMeaning() {
        var alternatives = parser.parse("shirts or dresses, formal or casual");
        assertThat(alternatives.constraints())
                .anySatisfy(
                        rule -> {
                            assertThat(rule.field()).isEqualTo("category");
                            assertThat(rule.value()).isEqualTo(java.util.List.of("DRESS", "TOP"));
                        });
        var excluded = parser.parse("not a t-shirt, except a dress, not smart casual");
        assertThat(excluded.constraints())
                .allSatisfy(rule -> assertThat(rule.operator()).isEqualTo("NOT_IN"));
        assertThat(excluded.constraints())
                .anySatisfy(
                        rule ->
                                assertThat(rule.value())
                                        .isEqualTo(java.util.List.of("DRESS", "TOP")));
        assertThat(new SmartQueryCompiler(clock).compile(excluded.query()).condition())
                .contains("NOT coalesce");
    }

    @Test
    void datesSizesAndWearCountsAreTypedAndUnknownDescriptionsStaySemantic() {
        var parsed =
                parser.parse(
                        "size M, worn fewer than 3 times, bought after 2025-04-01, not worn for 45 days");
        assertThat(parsed.constraints()).hasSize(4);
        assertThat(parsed.constraints())
                .anySatisfy(rule -> assertThat(rule.value()).isEqualTo("2026-08-17"));
        assertThat(new SmartQueryCompiler(clock).compile(parsed.query()).parameters().values())
                .contains(3, "m");
        assertThat(parser.parse("fluid silhouette with sculptural texture").query()).isNull();
        assertThat(parser.parse("blackbird formaldehyde topaz").constraints()).isEmpty();
    }
}
