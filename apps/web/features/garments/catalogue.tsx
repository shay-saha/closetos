"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Shirt } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "./queries";
import { categories, categoryNames } from "./types";
import { GarmentArt } from "./garment-art";
import { advancedFilters } from "./filters";
import { smartViews } from "@/features/collections/types";

const viewSorts: Record<string, string> = {
  RECENTLY_WORN: "RECENTLY_WORN",
  MOST_WORN: "MOST_WORN",
  LEAST_WORN: "LEAST_WORN",
  FORGOTTEN: "OLDEST",
  BEST_COST_PER_WEAR: "COST_PER_WEAR",
  HIGHEST_COST_PER_WEAR: "HIGH_COST_PER_WEAR",
};

export function Catalogue({
  collectionId,
  collectionName,
}: { collectionId?: string; collectionName?: string } = {}) {
  const params = useSearchParams();
  const router = useRouter();
  const basePath = collectionId ? `/collections/${collectionId}` : "/catalogue";
  const query = useGarments(params.toString(), 5 * 60_000, collectionId);
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const advancedFilterValues = JSON.stringify([
    ...advancedFilters.map(([key]) => params.get(key) ?? ""),
    params.get("processingStatus") ?? "",
  ]);
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete("cursor");
    if (key === "view") next.delete("sort");
    if (value) next.set(key, value);
    else next.delete(key);
    router.replace(`${basePath}?${next}`, { scroll: false });
  }
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Every piece, in one place</p>
          {collectionId ? <h2>Pieces in {collectionName}.</h2> : <h1>Your collection.</h1>}
          <p>A clearer view of what you own. Find a favourite, or see something with fresh eyes.</p>
        </div>
        <Link
          className="button button-secondary"
          href={collectionId ? "/catalogue" : "/collections"}
        >
          {collectionId ? "Full catalogue" : "Saved collections"}
        </Link>
      </div>
      <div className="filters">
        <form className="search" action={basePath}>
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
          Smart view
          <select
            value={params.get("view") ?? ""}
            onChange={(event) => update("view", event.target.value)}
          >
            <option value="">All pieces</option>
            {smartViews.map(([value, name]) => (
              <option key={value} value={value}>
                {name}
              </option>
            ))}
          </select>
        </label>
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
            value={params.get("sort") ?? viewSorts[params.get("view") ?? ""] ?? "NEWEST"}
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
      <details className="advanced-filters">
        <summary>More filters</summary>
        <form action={basePath} key={advancedFilterValues}>
          <div className="advanced-filter-grid">
            {advancedFilters.map(([key, label, type]) => (
              <label key={key}>
                {label}
                <input
                  name={key}
                  type={type}
                  defaultValue={params.get(key) ?? ""}
                  min={type === "number" ? 0 : undefined}
                  step={type === "number" ? 1 : undefined}
                  maxLength={
                    key === "brand" ? 120 : key === "subcategory" ? 80 : key === "size" ? 40 : 60
                  }
                />
              </label>
            ))}
            <label>
              Photo processing
              <select name="processingStatus" defaultValue={params.get("processingStatus") ?? ""}>
                <option value="">Any stage</option>
                {[
                  "DRAFT",
                  "AWAITING_UPLOAD",
                  "UPLOADED",
                  "PROCESSING_MEDIA",
                  "ANALYSING",
                  "READY_FOR_REVIEW",
                  "READY",
                  "FAILED",
                ].map((status) => (
                  <option key={status} value={status}>
                    {status.toLowerCase().replaceAll("_", " ")}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {Array.from(params.entries())
            .filter(
              ([key]) =>
                key !== "cursor" &&
                key !== "processingStatus" &&
                !advancedFilters.some(([field]) => field === key),
            )
            .map(([key, value]) => (
              <input key={key} type="hidden" name={key} value={value} />
            ))}
          <div className="form-actions">
            <Button type="submit" variant="secondary">
              Apply filters
            </Button>
            <Link href={basePath} className="button button-quiet">
              Clear filters
            </Link>
          </div>
        </form>
      </details>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : items.length === 0 ? (
        <div className="empty-state">
          <Shirt size={48} strokeWidth={1} />
          <h2>
            {collectionId
              ? "No matching pieces in this collection."
              : "Make room for your first piece."}
          </h2>
          <p>
            {collectionId
              ? "Adjust the saved rules or selected pieces above to widen this collection."
              : params.size
                ? "No pieces match these filters. Try widening your search."
                : "Start with the piece you reach for most. Your wardrobe will grow from there."}
          </p>
          <Link href={params.size ? basePath : "/add"} className="button button-primary">
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
