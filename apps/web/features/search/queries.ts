"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import { searchFilters, searchMode, type SearchPage } from "./types";
import type { SearchPhoto } from "./search-photos";

export function useWardrobeSearch(filters: string, photo?: SearchPhoto) {
  const params = new URLSearchParams(filters);
  return useInfiniteQuery({
    queryKey: ["search", filters],
    initialPageParam: null as string | null,
    enabled: !params.has("photo") || !!photo,
    gcTime: params.has("photo") ? 0 : 5 * 60_000,
    queryFn: ({ pageParam, signal }) => {
      const mode = searchMode(params);
      if (photo && params.has("similarToGarmentId"))
        throw new ApiError(400, "Choose either a reference piece or a photograph for this search.");
      for (const key of ["minWearCount", "maxWearCount"]) {
        const value = params.get(key);
        if (value && (!/^\d+$/.test(value) || Number(value) > 2_147_483_647))
          throw new ApiError(400, "Enter a whole number of wears between 0 and 2147483647.");
      }
      if (photo)
        return api<SearchPage>("search/image", {
          method: "POST",
          signal,
          body: JSON.stringify({
            filters: searchFilters(params, pageParam),
            mode,
            imageBase64: photo.base64,
          }),
        });
      const request = new URLSearchParams();
      for (const [key, value] of Object.entries(searchFilters(params, pageParam)))
        request.set(key, String(value));
      request.set("mode", mode);
      const source = params.get("similarToGarmentId");
      if (source) request.set("similarToGarmentId", source);
      return api<SearchPage>(`search?${request}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
    refetchInterval: (query) => {
      const page = query.state.data?.pages[0];
      return page &&
        page.mode !== "FILTERS" &&
        page.mode !== "KEYWORD" &&
        page.indexedCount < page.eligibleCount
        ? 5000
        : 5 * 60_000;
    },
    retry: (attempt, error) => attempt < 1 && (!(error instanceof ApiError) || error.status >= 500),
  });
}

export function useSearchSource(id: string | null) {
  return useQuery({
    queryKey: ["garment", id],
    enabled: !!id,
    queryFn: ({ signal }) => api<Garment>(`garments/${encodeURIComponent(id!)}`, { signal }),
  });
}
