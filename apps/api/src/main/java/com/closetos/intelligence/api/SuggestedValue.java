package com.closetos.intelligence.api;

import tools.jackson.databind.JsonNode;

public record SuggestedValue(JsonNode value, double confidence) {}
