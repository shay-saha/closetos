import type { Category, Garment } from "@/features/garments/types";

export type InsightSeason = "SPRING" | "SUMMER" | "AUTUMN" | "WINTER";
export type InsightView = "forgotten" | "usage" | "costs";

export type UsageInsights = {
  asOf: string;
  includesArchived: boolean;
  garmentCount: number;
  garmentWearOccurrences: number;
  neverWornCount: number;
  unknownLastWornCount: number;
  categories: Partial<Record<Category, number>>;
  availability: Partial<Record<Garment["status"], number>>;
  mostWorn: Garment[];
  leastWorn: Garment[];
  neverWorn: Garment[];
  explanations: string[];
};
export type CurrencyCosts = {
  currency: string;
  pricedGarmentCount: number;
  knownPurchaseTotal: number;
  lowestCostPerWear: Garment[];
  highestCostPerWear: Garment[];
  neverWorn: Garment[];
};
export type CostInsights = {
  asOf: string;
  includesArchived: boolean;
  garmentCount: number;
  missingPriceCount: number;
  missingCurrencyCount: number;
  currencies: CurrencyCosts[];
  explanations: string[];
};
export type ForgottenPiece = {
  garment: Garment;
  ownedSince: string;
  ownershipBasis: "PURCHASE_DATE" | "ADDED_DATE";
  ownershipDays: number;
  daysSinceLastWear: number | null;
  seasonCompatibility: "MATCH" | "MISMATCH" | "UNKNOWN" | "NOT_REQUESTED";
  reasons: string[];
};
export type ForgottenInsights = {
  asOf: string;
  season: InsightSeason | null;
  minimumOwnershipDays: number;
  minimumDaysSinceWear: number;
  garmentCount: number;
  eligibleCount: number;
  unknownLastWornCount: number;
  items: ForgottenPiece[];
  explanations: string[];
};
export type InsightResult =
  | { view: "forgotten"; data: ForgottenInsights }
  | { view: "usage"; data: UsageInsights }
  | { view: "costs"; data: CostInsights };
