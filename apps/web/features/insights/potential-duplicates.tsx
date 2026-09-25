"use client";

import { startTransition, useOptimistic } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { categories, categoryNames, type Category } from "@/features/garments/types";
import { CalculationDetails, InsightPiece } from "./wardrobe-insights";
import { useDuplicates } from "./recommendation-queries";
import { ComparisonNotice, RecommendationReasons } from "./recommendation-notice";

export function PotentialDuplicates() {
  const url = useSearchParams();
  const router = useRouter();
  const [draft, edit] = useOptimistic(
    url.toString(),
    (current, change: { key: string; value: string }) => {
      const next = new URLSearchParams(current);
      if (change.value) next.set(change.key, change.value);
      else next.delete(change.key);
      return next.toString();
    },
  );
  const params = new URLSearchParams(draft);
  const requestedCategory = params.get("category") ?? "";
  const category = categories.includes(requestedCategory as Category) ? requestedCategory : "";
  const requestedLimit = Number(params.get("limit") ?? "12");
  const limit = [12, 24, 60, 100].includes(requestedLimit) ? requestedLimit : 12;
  const query = useDuplicates(category, limit);
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    startTransition(() => {
      edit({ key, value });
      router.push(`/discover/duplicates?${next}`, { scroll: false });
    });
  }
  const data = query.data;
  return (
    <div className="page insights-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A second look</p>
          <h1>Potential duplicates.</h1>
          <p>
            Similar photos and recorded colours can reveal pieces worth comparing. These are
            suggestions to review; nothing is changed automatically.
          </p>
        </div>
        <Link href="/discover/insights" className="button button-secondary">
          Wardrobe insights
        </Link>
      </div>
      <div className="insight-toolbar">
        <label>
          Category
          <select value={category} onChange={(event) => update("category", event.target.value)}>
            <option value="">All categories</option>
            {categories.map((value) => (
              <option key={value} value={value}>
                {categoryNames[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Pairs per list
          <select
            value={limit}
            onChange={(event) =>
              update("limit", event.target.value === "12" ? "" : event.target.value)
            }
          >
            {[12, 24, 60, 100].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <Button variant="quiet" disabled={query.isFetching} onClick={() => void query.refetch()}>
          {query.isFetching ? "Refreshing…" : "Refresh comparisons"}
        </Button>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : (
        data && (
          <>
            <ComparisonNotice coverage={data.embeddings} photoOnly />
            <p className="insight-context">
              {data.photoGarmentCount} of {data.reviewedGarmentCount} reviewed pieces have approved
              photos in this view. Pieces without photos are not compared.
            </p>
            {data.missingColourCount > 0 && (
              <p className="processing-notice">
                {data.missingColourCount} photo{" "}
                {data.missingColourCount === 1 ? "piece has" : "pieces have"} no recorded colour and
                cannot establish a colour match.
              </p>
            )}
            {data.truncated && (
              <p className="processing-notice">
                Comparing a stable selection of up to 2,000 photo pieces. Choose a category to
                narrow this view.
              </p>
            )}
            {data.items.length ? (
              <>
                <p className="insight-result-count" role="status">
                  Showing {data.items.length} of {data.candidatePairCount} potential matches in this
                  comparison shortlist.
                </p>
                <ol className="duplicate-pairs">
                  {data.items.map((pair) => (
                    <li key={`${pair.first.id}:${pair.second.id}`}>
                      <section aria-label={`Compare ${pair.first.name} and ${pair.second.name}`}>
                        <h2>A potential match</h2>
                        <InsightPiece garment={pair.first} />
                        <InsightPiece garment={pair.second} />
                        <RecommendationReasons reasons={pair.reasons} />
                      </section>
                    </li>
                  ))}
                </ol>
              </>
            ) : (
              <div className="empty-state">
                <h2>
                  {data.photoGarmentCount < 2
                    ? "A little more to compare."
                    : data.embeddings.state === "UNAVAILABLE"
                      ? "Comparisons need another try."
                      : data.embeddings.state === "PENDING"
                        ? "Comparisons are still being prepared."
                        : "No potential matches in this shortlist."}
                </h2>
                <p>
                  {data.photoGarmentCount < 2
                    ? "Add and review photos for at least two pieces in this view to compare them."
                    : data.embeddings.state === "READY"
                      ? "No compared pairs met the category, photo-feature and colour rules. This shortlist is advisory and does not rule out other duplicates."
                      : "Comparisons are still incomplete. Review any missing colour details and refresh when photo comparisons are available."}
                </p>
                <Link href="/catalogue" className="button button-secondary">
                  Browse pieces
                </Link>
              </div>
            )}
            <CalculationDetails explanations={data.explanations} />
          </>
        )
      )}
    </div>
  );
}
