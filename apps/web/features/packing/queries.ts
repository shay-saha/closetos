"use client";

import { useInfiniteQuery, useQuery, type QueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PackingList, PackingPage } from "./types";

export function usePackingLists(query: string) {
  return useInfiniteQuery({
    queryKey: ["packing", "lists", query],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ q: query, limit: "40" });
      if (pageParam) params.set("cursor", pageParam);
      return api<PackingPage>(`packing-lists?${params}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
  });
}
export function usePackingList(id: string) {
  return useQuery({
    queryKey: ["packing", "detail", id],
    queryFn: ({ signal }) => api<PackingList>(`packing-lists/${id}`, { signal }),
    refetchInterval: 5 * 60_000,
  });
}
export async function refreshPacking(client: QueryClient, saved: PackingList) {
  client.setQueryData(["packing", "detail", saved.id], saved);
  await Promise.all(
    ["packing", "garments", "garment", "search"].map((key) =>
      client.invalidateQueries({ queryKey: [key] }),
    ),
  );
}
