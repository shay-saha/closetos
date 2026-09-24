package com.closetos.packing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PackingSolverConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(PackingSolverConfiguration.class);

    @Test
    void limitsAndAllObjectiveWeightsBindWithDocumentedDefaultsAndOverrides() {
        context.run(
                application -> {
                    assertThat(application).hasNotFailed();
                    var settings = application.getBean(PackingSolverSettings.class);
                    assertThat(settings.maximumSeconds()).isEqualTo(10);
                    assertThat(settings.maximumConcurrent()).isEqualTo(2);
                    assertThat(settings.weights().itemCount()).isEqualTo(10_000);
                    assertThat(settings.weights().outfitVariety()).isEqualTo(1_000);
                });
        context.withPropertyValues(
                        "closetos.packing.solver.maximum-seconds=4",
                        "closetos.packing.solver.maximum-concurrent=1",
                        "closetos.packing.solver.weights.underuse=12")
                .run(
                        application -> {
                            assertThat(application).hasNotFailed();
                            var settings = application.getBean(PackingSolverSettings.class);
                            assertThat(settings.maximumSeconds()).isEqualTo(4);
                            assertThat(settings.maximumConcurrent()).isEqualTo(1);
                            assertThat(settings.weights().underuse()).isEqualTo(12);
                            assertThat(settings.weights().redundancy()).isEqualTo(300);
                        });
    }

    @Test
    void invalidTimeCapacityAndWeightSettingsFailStartup() {
        for (String value :
                new String[] {
                    "maximum-seconds=NaN",
                    "maximum-seconds=0",
                    "maximum-seconds=61",
                    "maximum-deterministic-time=0",
                    "maximum-concurrent=0",
                    "maximum-concurrent=5",
                    "weights.item-count=0",
                    "weights.outfit-variety=-1",
                    "weights.underuse=1000001"
                })
            context.withPropertyValues("closetos.packing.solver." + value)
                    .run(application -> assertThat(application).hasFailed());
    }
}
