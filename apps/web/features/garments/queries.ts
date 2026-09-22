"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Garment, GarmentPage } from "./types";

export function useGarments(filters: string, refetchInterval = 10 * 60_000, collectionId?: string) {
  return useInfiniteQuery({
    queryKey: ["garments", collectionId ?? null, filters],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams(filters);
      params.set("limit", "60");
      if (pageParam) params.set("cursor", pageParam);
      const endpoint = collectionId
        ? `collections/${encodeURIComponent(collectionId)}/garments`
        : "garments";
      return api<GarmentPage>(`${endpoint}?${params}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
    refetchInterval,
  });
}

export function useGarment(id: string) {
  return useQuery({
    queryKey: ["garment", id],
    queryFn: ({ signal }) => api<Garment>(`garments/${id}`, { signal }),
    refetchInterval: (query) =>
      query.state.data &&
      ["AWAITING_UPLOAD", "UPLOADED", "PROCESSING_MEDIA", "ANALYSING"].includes(
        query.state.data.processingStatus,
      )
        ? 2000
        : 10 * 60_000,
  });
}
