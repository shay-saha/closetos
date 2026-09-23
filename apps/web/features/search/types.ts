import type { Garment } from "@/features/garments/types";
import { advancedFilters } from "@/features/garments/filters";

export type SearchMode = "FILTERS" | "KEYWORD" | "SEMANTIC" | "HYBRID";
export type SearchPage = {
  items: { garment: Garment; explanations: string[] }[];
  nextCursor: string | null;
  mode: SearchMode;
  appliedConstraints: { field: string; operator: string; value: unknown; explanation: string }[];
  eligibleCount: number;
  indexedCount: number;
};

export const searchFields = [
  ...advancedFilters.map(([key]) => key),
  "category",
  "status",
  "processingStatus",
  "view",
  "sort",
  "q",
] as const;

export function searchParameters(input: URLSearchParams) {
  const params = new URLSearchParams();
  for (const field of [...searchFields, "mode", "similarToGarmentId", "photo"]) {
    const value = input.get(field)?.trim();
    if (value) params.set(field, value);
  }
  params.sort();
  return params;
}

export function searchFilters(params: URLSearchParams, cursor: string | null) {
  const filters: Record<string, string | number> = { limit: 40 };
  for (const key of searchFields) {
    const value = params.get(key);
    if (value)
      filters[key] = key === "minWearCount" || key === "maxWearCount" ? Number(value) : value;
  }
  if (cursor) filters.cursor = cursor;
  return filters;
}

export function searchMode(params: URLSearchParams): SearchMode {
  if (!params.get("q") && !params.get("photo") && !params.get("similarToGarmentId"))
    return "FILTERS";
  return (params.get("mode") ?? "HYBRID") as SearchMode;
}
