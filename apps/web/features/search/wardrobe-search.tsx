"use client";

import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import { Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { GarmentArt } from "@/features/garments/garment-art";
import { categoryNames } from "@/features/garments/types";
import { advancedFilters } from "@/features/garments/filters";
import { ApiError } from "@/lib/api";
import { useSearchPhotos } from "./search-photos";
import { useWardrobeSearch } from "./queries";
import { SearchForm } from "./search-form";
import { searchParameters } from "./types";

const filterNames: Record<string, string> = {
  ...Object.fromEntries(advancedFilters.map(([key, label]) => [key, label])),
  category: "Category",
  status: "Availability",
  processingStatus: "Photo processing",
  view: "Smart view",
};

export function WardrobeSearch() {
  const url = useSearchParams();
  const router = useRouter();
  const params = searchParameters(new URLSearchParams(url));
  const filters = params.toString();
  const { photos } = useSearchPhotos();
  const photo = photos.find((item) => item.id === params.get("photo"));
  const missingPhoto = params.has("photo") && !photo;
  const query = useWardrobeSearch(filters, photo);
  const page = query.data?.pages[0];
  const items = query.data?.pages.flatMap((result) => result.items) ?? [];
  const chosenFilters = Array.from(params).filter(([key]) => key in filterNames);
  function update(key: string, value?: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    router.push(`/search?${next}`, { scroll: false });
  }
  const sourcePending =
    query.error instanceof ApiError && query.error.code === "SEARCH_SOURCE_PENDING";
  const unavailable = query.error instanceof ApiError && query.error.status === 503;
  const limited = query.error instanceof ApiError && query.error.code === "ACTION_LIMIT";
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A different way to find a favourite</p>
          <h1>Find what feels right.</h1>
          <p>
            A description, a photograph, or a piece you already love. Discover what is waiting in
            your wardrobe.
          </p>
        </div>
        <div className="form-actions">
          <Link href="/discover/insights" className="button button-secondary">
            Wardrobe insights
          </Link>
          <Link href="/discover/topology" className="button button-secondary">
            Wardrobe topology
          </Link>
          <Link href="/search" className="button button-quiet">
            Start again
          </Link>
        </div>
      </div>
      <SearchForm key={filters} filters={filters} />
      {chosenFilters.length > 0 && (
        <div className="search-constraints" aria-label="Your selected filters">
          {chosenFilters.map(([key, value]) => (
            <Button
              key={key}
              variant="secondary"
              aria-label={`Remove ${filterNames[key]} filter`}
              onClick={() => update(key)}
            >
              {filterNames[key]}: {value.toLowerCase().replaceAll("_", " ")} ×
            </Button>
          ))}
        </div>
      )}
      {page && page.appliedConstraints.length > 0 && (
        <section
          className="search-interpretation"
          aria-label="Filters recognised in your description"
        >
          <h2>From your description</h2>
          <ul>
            {page.appliedConstraints.map((constraint, index) => (
              <li key={`${constraint.field}-${index}`}>{constraint.explanation}</li>
            ))}
          </ul>
          <Button variant="quiet" onClick={() => update("mode", "SEMANTIC")}>
            Use description without these filters
          </Button>
        </section>
      )}
      {missingPhoto ? (
        <div className="feedback" role="status">
          <h2>Select your photograph again.</h2>
          <p>
            Photograph queries stay in this browser session. Choose the reference photograph above,
            or remove it to search by description.
          </p>
          <Button variant="secondary" onClick={() => update("photo")}>
            Continue without a photograph
          </Button>
        </div>
      ) : query.isPending ? (
        <LoadingState />
      ) : query.isError && !page ? (
        <>
          {sourcePending && (
            <p className="processing-notice" role="status">
              Your reference piece is still being prepared for similarity search. You can keep using
              it in your wardrobe and try this search again shortly.
            </p>
          )}
          <ErrorState error={query.error} retry={query.refetch} />
          {(unavailable || limited) &&
            !params.has("photo") &&
            !params.has("similarToGarmentId") &&
            params.has("q") && (
              <Button variant="secondary" onClick={() => update("mode", "KEYWORD")}>
                Search words in details instead
              </Button>
            )}
        </>
      ) : (
        <>
          {query.isRefetchError && (
            <div className="processing-notice" role="alert">
              <p>These results could not be refreshed. Try again to see recent changes.</p>
              <Button variant="secondary" onClick={() => query.refetch()}>
                Refresh results
              </Button>
            </div>
          )}
          {page &&
            page.mode !== "FILTERS" &&
            page.mode !== "KEYWORD" &&
            page.indexedCount < page.eligibleCount && (
              <p className="processing-notice" role="status">
                {page.indexedCount} of {page.eligibleCount} matching pieces are ready for similarity
                search. More will appear as they are prepared; you can still browse the full
                catalogue.
              </p>
            )}
          {items.length === 0 ? (
            <div className="empty-state">
              <Search size={48} strokeWidth={1} aria-hidden="true" />
              <h2>No pieces found yet.</h2>
              <p>
                Try a broader description, remove a filter, or add another piece to your wardrobe.
              </p>
              <Link href="/catalogue" className="button button-secondary">
                Browse your collection
              </Link>
            </div>
          ) : (
            <>
              <div className="search-results-heading">
                <h2>
                  {page?.mode === "FILTERS"
                    ? "Your pieces"
                    : page?.mode === "KEYWORD"
                      ? "Matching pieces"
                      : "Related pieces"}
                </h2>
                <p className="small" role="status">
                  {items.length} pieces shown
                  {query.isFetching && !query.isFetchingNextPage ? " · Updating…" : ""}
                </p>
              </div>
              <div className="catalogue-grid search-results">
                {items.map(({ garment, explanations }) => (
                  <Link className="piece-card" key={garment.id} href={`/garments/${garment.id}`}>
                    <GarmentArt garment={garment} />
                    <h3>{garment.name}</h3>
                    <p>
                      {[
                        categoryNames[garment.category],
                        garment.primaryColourName,
                        garment.status !== "AVAILABLE" ? garment.status.toLowerCase() : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </p>
                    {explanations.length > 0 && (
                      <ul className="search-match-reasons" aria-label="Why this piece appears">
                        {explanations.map((reason) => (
                          <li key={reason}>{reason}</li>
                        ))}
                      </ul>
                    )}
                  </Link>
                ))}
              </div>
              <div className="load-more">
                {query.hasNextPage && (
                  <Button
                    variant="secondary"
                    disabled={query.isFetchingNextPage}
                    onClick={() => query.fetchNextPage()}
                  >
                    {query.isFetchingNextPage ? "Finding more…" : "Show more pieces"}
                  </Button>
                )}
                {query.isFetchNextPageError && (
                  <div role="alert">
                    More pieces could not be loaded. Try again using the button above.
                    <Button
                      variant="quiet"
                      disabled={query.isFetching}
                      onClick={() => query.refetch()}
                    >
                      Refresh results
                    </Button>
                  </div>
                )}
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
