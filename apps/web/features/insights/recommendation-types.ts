import type { Garment } from "@/features/garments/types";
import type { InsightSeason } from "./types";

export const weatherNames = { COLD: "Cold", MILD: "Mild", HOT: "Hot", RAIN: "Rain" } as const;
export type RecommendationWeather = keyof typeof weatherNames;
export type EmbeddingCoverage = {
  state: "READY" | "PENDING" | "UNAVAILABLE" | "NOT_NEEDED";
  model: { provider: string; modelId: string; dimensions: number } | null;
  eligibleCount: number;
  indexedCount: number;
};
export type DuplicateInsights = {
  reviewedGarmentCount: number;
  photoGarmentCount: number;
  missingColourCount: number;
  candidatePairCount: number;
  truncated: boolean;
  embeddings: EmbeddingCoverage;
  items: { first: Garment; second: Garment; reasons: string[] }[];
  explanations: string[];
};
export type PairingContext = {
  season: InsightSeason | null;
  formality: string | null;
  weather: RecommendationWeather | null;
};
export type WorksWithInsights = {
  source: Garment;
  context: PairingContext;
  sourceMatchesContext: boolean;
  eligibleCount: number;
  embeddings: EmbeddingCoverage;
  items: {
    garment: Garment;
    wornTogetherCount: number;
    savedTogetherCount: number;
    semanticAffinityKnown: boolean;
    reasons: string[];
  }[];
  explanations: string[];
};
