"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { WardrobeGraph } from "./types";

export function useTopology(category: string) {
  const params = new URLSearchParams();
  if (category) params.set("category", category);
  return useQuery({
    queryKey: ["search", "topology", category],
    queryFn: ({ signal }) => api<WardrobeGraph>(`insights/topology?${params}`, { signal }),
    refetchInterval: (query) =>
      query.state.data &&
      (query.state.data.embeddingAvailability === "UNAVAILABLE" ||
        query.state.data.nodes.some((node) => !node.indexed))
        ? 5000
        : 5 * 60_000,
  });
}
