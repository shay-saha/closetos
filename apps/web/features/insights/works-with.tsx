"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { ApiError } from "@/lib/api";
import { CalculationDetails, InsightPiece } from "./wardrobe-insights";
import { useWorksWith } from "./recommendation-queries";
import { ComparisonNotice, RecommendationReasons } from "./recommendation-notice";
import { weatherNames, type PairingContext } from "./recommendation-types";
import type { InsightSeason } from "./types";

const seasons = { SPRING: "Spring", SUMMER: "Summer", AUTUMN: "Autumn", WINTER: "Winter" } as const;
export function WorksWith({ id }: { id: string }) {
  const params = useSearchParams();
  const router = useRouter();
  const season = params.get("season") ?? "";
  const weather = params.get("weather") ?? "";
  const requestedLimit = Number(params.get("limit") ?? "12");
  const limit = [12, 24, 60, 100].includes(requestedLimit) ? requestedLimit : 12;
  const context: PairingContext = {
    season: Object.hasOwn(seasons, season) ? (season as InsightSeason) : null,
    weather: Object.hasOwn(weatherNames, weather) ? (weather as keyof typeof weatherNames) : null,
    formality: params.get("formality")?.trim() || null,
  };
  const query = useWorksWith(id, context, limit);
  const data = query.data;
  return (
    <div className="page insights-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A little inspiration</p>
          <h1>Works with this.</h1>
          <p>
            Available pieces that complement a garment you love, guided by your recorded details,
            outfits and wear history.
          </p>
        </div>
        <Link href={`/garments/${id}`} className="button button-secondary">
          Back to piece
        </Link>
      </div>
      <form
        key={params.toString()}
        className="insight-toolbar"
        onSubmit={(event) => {
          event.preventDefault();
          const values = new FormData(event.currentTarget);
          const next = new URLSearchParams();
          for (const key of ["season", "weather", "formality", "limit"]) {
            const value = String(values.get(key) ?? "").trim();
            if (value && !(key === "limit" && value === "12")) next.set(key, value);
          }
          router.push(`/garments/${id}/works-with?${next}`, { scroll: false });
        }}
      >
        <label>
          Season
          <select name="season" defaultValue={context.season ?? ""}>
            <option value="">Any season</option>
            {Object.entries(seasons).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Weather assumption
          <select name="weather" defaultValue={context.weather ?? ""}>
            <option value="">No weather filter</option>
            {Object.entries(weatherNames).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Formality
          <input
            name="formality"
            defaultValue={context.formality ?? ""}
            maxLength={60}
            placeholder="For example, Casual"
          />
        </label>
        <label>
          Suggestions per list
          <select name="limit" defaultValue={limit}>
            {[12, 24, 60, 100].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <Button type="submit" variant="secondary">
          Apply filters
        </Button>
        <Button
          type="button"
          variant="quiet"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          {query.isFetching ? "Refreshing…" : "Refresh suggestions"}
        </Button>
      </form>
      <p className="insight-context">
        Filters apply to both pieces. Weather uses explicit tags such as “cold-weather”,
        “hot-weather”, “mild-weather” or “rain”. Missing tags do not count as a match.
      </p>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <>
          <ErrorState error={query.error} retry={query.refetch} />
          {query.error instanceof ApiError && query.error.code === "PAIRING_SOURCE_UNAVAILABLE" && (
            <Link href={`/garments/${id}`} className="button button-secondary">
              Review this piece’s availability
            </Link>
          )}
        </>
      ) : (
        data && (
          <>
            <section className="pairing-source" aria-label="Selected piece">
              <h2>Start with this piece</h2>
              <InsightPiece garment={data.source} />
              <Link className="button button-secondary" href={`/studio?garment=${id}`}>
                Build an outfit
              </Link>
            </section>
            <ComparisonNotice coverage={data.embeddings} />
            {!data.sourceMatchesContext && (
              <p className="processing-notice" role="status">
                The selected piece does not meet the requested filters. Adjust the filters or update
                its recorded details.
              </p>
            )}
            {data.sourceMatchesContext && (
              <p className="insight-result-count" role="status">
                Showing {data.items.length} of {data.eligibleCount} eligible pieces, ranked by how
                they complement the selected piece.
              </p>
            )}
            {data.items.length ? (
              <ol className="pairing-pieces">
                {data.items.map((item) => (
                  <li key={item.garment.id}>
                    <InsightPiece garment={item.garment} />
                    <RecommendationReasons reasons={item.reasons} />
                  </li>
                ))}
              </ol>
            ) : (
              <div className="empty-state">
                <h2>No compatible pieces in this view.</h2>
                <p>
                  {data.sourceMatchesContext
                    ? "Try fewer filters, review missing tags, or make a piece available again. Pieces in Other or unclear accessory slots need more specific categories before compatibility can be established."
                    : "The selected piece needs to match the requested season, formality and weather tags before pairings can be suggested."}
                </p>
                <Link className="button button-secondary" href={`/garments/${id}`}>
                  Review selected piece
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
