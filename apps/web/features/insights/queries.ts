"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type {
  CostInsights,
  ForgottenInsights,
  InsightResult,
  InsightSeason,
  InsightView,
  UsageInsights,
} from "./types";

export function useInsights(
  view: InsightView,
  season: InsightSeason | "",
  includeArchived: boolean,
  limit: number,
) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (view === "forgotten") {
    if (season) params.set("season", season);
  } else if (includeArchived) params.set("includeArchived", "true");
  return useQuery({
    queryKey: ["search", "insights", view, params.toString()],
    queryFn: async ({ signal }): Promise<InsightResult> => {
      if (view === "forgotten")
        return {
          view,
          data: await api<ForgottenInsights>(`insights/forgotten?${params}`, { signal }),
        };
      if (view === "costs")
        return {
          view,
          data: await api<CostInsights>(`insights/cost-per-wear?${params}`, { signal }),
        };
      return { view, data: await api<UsageInsights>(`insights/usage?${params}`, { signal }) };
    },
    refetchInterval: 5 * 60_000,
  });
}
