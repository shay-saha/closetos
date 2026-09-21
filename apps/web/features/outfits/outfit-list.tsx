"use client";

import { useState } from "react";
import Link from "next/link";
import { useInfiniteQuery, useQueries } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import type { Garment } from "@/features/garments/types";
import { api } from "@/lib/api";
import { OutfitCanvas } from "./outfit-canvas";
import type { Outfit, OutfitPage } from "./types";

function OutfitCard({ outfit }: { outfit: Outfit }) {
  const pieces = useQueries({
    queries: outfit.items.map((item) => ({
      queryKey: ["garment", item.garmentId],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api<Garment>(`garments/${item.garmentId}`, { signal }),
    })),
  });
  const garments = new Map(
    pieces.flatMap((query) => (query.data ? [[query.data.id, query.data] as const] : [])),
  );
  return (
    <Link href={`/outfits/${outfit.id}`} className="outfit-card">
      <OutfitCanvas items={outfit.items} garments={garments} />
      <h2>{outfit.name}</h2>
      <p className="small">
        {[outfit.occasion, outfit.season, outfit.rating ? `${outfit.rating} / 5` : null]
          .filter(Boolean)
          .join(" · ") || `${outfit.items.length} pieces`}
      </p>
    </Link>
  );
}

export function OutfitList() {
  const [archived, setArchived] = useState(false);
  const query = useInfiniteQuery({
    queryKey: ["outfits", archived],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ archived: String(archived) });
      if (pageParam) params.set("cursor", pageParam);
      return api<OutfitPage>(`outfits?${params}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
  });
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Combinations worth keeping</p>
          <h1>Your saved outfits.</h1>
          <p>A favourite pairing, ready for another day.</p>
        </div>
        <Link href="/studio" className="button button-primary">
          Create an outfit
        </Link>
      </div>
      <div className="category-tabs" aria-label="Outfit status">
        <Button variant="secondary" aria-pressed={!archived} onClick={() => setArchived(false)}>
          Active outfits
        </Button>
        <Button variant="secondary" aria-pressed={archived} onClick={() => setArchived(true)}>
          Archived outfits
        </Button>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : !items.length ? (
        <div className="empty-state">
          <h2>{archived ? "No archived outfits." : "Your next combination starts here."}</h2>
          <p>Open the studio and bring a few favourite pieces together.</p>
        </div>
      ) : (
        <div className="outfit-grid">
          {items.map((outfit) => (
            <OutfitCard key={outfit.id} outfit={outfit} />
          ))}
        </div>
      )}
      {query.hasNextPage && (
        <div className="load-more">
          <Button
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            More outfits
          </Button>
        </div>
      )}
    </div>
  );
}
