"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type {
  DuplicateInsights,
  EmbeddingCoverage,
  PairingContext,
  WorksWithInsights,
} from "./recommendation-types";

function refreshInterval(coverage?: EmbeddingCoverage) {
  return coverage?.state === "PENDING"
    ? 5000
    : coverage?.state === "UNAVAILABLE"
      ? 15_000
      : 5 * 60_000;
}
export function useDuplicates(category: string, limit: number) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (category) params.set("category", category);
  return useQuery({
    queryKey: ["search", "duplicates", params.toString()],
    queryFn: ({ signal }) => api<DuplicateInsights>(`insights/duplicates?${params}`, { signal }),
    refetchInterval: (query) => refreshInterval(query.state.data?.embeddings),
  });
}
export function useWorksWith(id: string, context: PairingContext, limit: number) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (context.season) params.set("season", context.season);
  if (context.formality) params.set("formality", context.formality);
  if (context.weather) params.set("weather", context.weather);
  return useQuery({
    queryKey: ["search", "works-with", id, params.toString()],
    queryFn: ({ signal }) =>
      api<WorksWithInsights>(`garments/${encodeURIComponent(id)}/works-with?${params}`, { signal }),
    refetchInterval: (query) => refreshInterval(query.state.data?.embeddings),
  });
}
