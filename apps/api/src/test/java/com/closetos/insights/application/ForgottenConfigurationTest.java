package com.closetos.insights.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ForgottenConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(InsightConfiguration.class);

    @Test
    void defaultsAndEnvironmentOverridesBindThroughSpring() {
        context.run(
                application -> {
                    assertThat(application).hasNotFailed();
                    var settings = application.getBean(ForgottenRankingProperties.class);
                    assertThat(settings.minimumOwnershipDays()).isEqualTo(90);
                    assertThat(settings.weights().recency()).isEqualTo(2);
                    assertThat(settings.availability().laundry()).isEqualTo(.35);
                });
        context.withPropertyValues(
                        "closetos.insights.forgotten.minimum-ownership-days=120",
                        "closetos.insights.forgotten.weights.recency=4.5",
                        "closetos.insights.forgotten.availability.packed=0.2")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            var settings = application.getBean(ForgottenRankingProperties.class);
                            assertThat(settings.minimumOwnershipDays()).isEqualTo(120);
                            assertThat(settings.weights().recency()).isEqualTo(4.5);
                            assertThat(settings.weights().ownership()).isEqualTo(1);
                            assertThat(settings.availability().packed()).isEqualTo(.2);
                        });
    }

    @Test
    void invalidThresholdsAndWeightsFailStartup() {
        for (String invalid :
                new String[] {
                    "closetos.insights.forgotten.minimum-ownership-days=0",
                    "closetos.insights.forgotten.minimum-days-since-wear=3651",
                    "closetos.insights.forgotten.weights.recency=NaN",
                    "closetos.insights.forgotten.availability.laundry=1.1"
                })
            context.withPropertyValues(invalid)
                    .run(application -> assertThat(application).hasFailed());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForgottenRankingProperties.Weights(0, 0, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ForgottenRankingProperties.Weights(
                                        Double.POSITIVE_INFINITY, 1, 1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ForgottenRankingProperties.Availability(-.1, .1, .1));
    }
}
