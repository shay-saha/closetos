"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Shirt } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "./queries";
import { categories, categoryNames } from "./types";
import { GarmentArt } from "./garment-art";

export function Catalogue() {
  const params = useSearchParams();
  const router = useRouter();
  const query = useGarments(params.toString());
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete("cursor");
    if (value) next.set(key, value);
    else next.delete(key);
    router.replace(`/catalogue?${next}`, { scroll: false });
  }
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Every piece, in one place</p>
          <h1>Your collection.</h1>
          <p>A clearer view of what you own. Find a favourite, or see something with fresh eyes.</p>
        </div>
        <Link className="button button-secondary" href="/wardrobe">
          Browse the rail
        </Link>
      </div>
      <div className="filters">
        <form className="search" action="/catalogue">
          <label>
            Find a piece
            <input
              name="q"
              type="search"
              defaultValue={params.get("q") ?? ""}
              placeholder="Name, colour, material…"
            />
          </label>
          {Array.from(params.entries())
            .filter(([key]) => key !== "q" && key !== "cursor")
            .map(([key, value]) => (
              <input key={key} name={key} value={value} type="hidden" />
            ))}
        </form>
        <label>
          Category
          <select
            value={params.get("category") ?? ""}
            onChange={(event) => update("category", event.target.value)}
          >
            <option value="">All pieces</option>
            {categories.map((category) => (
              <option key={category} value={category}>
                {categoryNames[category]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Availability
          <select
            value={params.get("status") ?? ""}
            onChange={(event) => update("status", event.target.value)}
          >
            <option value="">All active</option>
            {["AVAILABLE", "LAUNDRY", "PACKED", "LENT", "ARCHIVED"].map((status) => (
              <option key={status} value={status}>
                {status.toLowerCase()}
              </option>
            ))}
          </select>
        </label>
        <label>
          Order
          <select
            value={params.get("sort") ?? "NEWEST"}
            onChange={(event) => update("sort", event.target.value)}
          >
            <option value="NEWEST">Newest first</option>
            <option value="OLDEST">Oldest first</option>
            <option value="NAME">Name</option>
            <option value="MOST_WORN">Most worn</option>
            <option value="LEAST_WORN">Least worn</option>
            <option value="RECENTLY_WORN">Recently worn</option>
            <option value="COST_PER_WEAR">Best cost per wear</option>
            <option value="HIGH_COST_PER_WEAR">Highest cost per wear</option>
          </select>
        </label>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : items.length === 0 ? (
        <div className="empty-state">
          <Shirt size={48} strokeWidth={1} />
          <h2>Make room for your first piece.</h2>
          <p>
            {params.size
              ? "No pieces match these filters. Try widening your search."
              : "Start with the piece you reach for most. Your wardrobe will grow from there."}
          </p>
          <Link href={params.size ? "/catalogue" : "/add"} className="button button-primary">
            {params.size ? "Clear filters" : "Add your first piece"}
          </Link>
        </div>
      ) : (
        <>
          <div className="catalogue-grid">
            {items.map((garment) => (
              <Link className="piece-card" key={garment.id} href={`/garments/${garment.id}`}>
                <GarmentArt garment={garment} />
                <h2>{garment.name}</h2>
                <p>
                  {[
                    categoryNames[garment.category],
                    garment.primaryColourName,
                    garment.status !== "AVAILABLE" ? garment.status.toLowerCase() : null,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
              </Link>
            ))}
          </div>
          <div className="load-more">
            <p className="small" aria-live="polite">
              {items.length} pieces shown
            </p>
            {query.hasNextPage && (
              <Button
                variant="secondary"
                disabled={query.isFetchingNextPage}
                onClick={() => query.fetchNextPage()}
              >
                {query.isFetchingNextPage ? "Finding more…" : "Show more pieces"}
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
