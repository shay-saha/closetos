package com.closetos.packing.infrastructure;

import com.closetos.packing.application.PackingObjective;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@org.springframework.validation.annotation.Validated
@ConfigurationProperties("closetos.packing.solver")
public record PackingSolverSettings(
        @DefaultValue("10") double maximumSeconds,
        @DefaultValue("5") double maximumDeterministicTime,
        @DefaultValue("2") int maximumConcurrent,
        @DefaultValue @NotNull @Valid PackingObjective.Weights weights) {
    public PackingSolverSettings {
        if (!Double.isFinite(maximumSeconds)
                || maximumSeconds < .1
                || maximumSeconds > 60
                || !Double.isFinite(maximumDeterministicTime)
                || maximumDeterministicTime < .001
                || maximumDeterministicTime > 60
                || maximumConcurrent < 1
                || maximumConcurrent > 4
                || weights == null)
            throw new IllegalArgumentException(
                    "Packing solver limits must be finite, positive, and within the supported bounds.");
    }
}
