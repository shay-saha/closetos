"use client";

import { startTransition, useOptimistic } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronRight, Sprout } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { GarmentArt } from "@/features/garments/garment-art";
import { categories, categoryNames, type Garment } from "@/features/garments/types";
import { useInsights } from "./queries";
import type {
  CostInsights,
  ForgottenInsights,
  InsightSeason,
  InsightView,
  UsageInsights,
} from "./types";

const views: Record<InsightView, string> = {
  forgotten: "Forgotten pieces",
  usage: "Wear history",
  costs: "Cost per wear",
};
const seasons: Record<InsightSeason, string> = {
  SPRING: "Spring",
  SUMMER: "Summer",
  AUTUMN: "Autumn",
  WINTER: "Winter",
};
const availability: Record<Garment["status"], string> = {
  AVAILABLE: "Available",
  LAUNDRY: "In the laundry",
  PACKED: "Packed",
  LENT: "Lent",
  ARCHIVED: "Archived",
};

function money(amount: number | null | undefined, currency: string | null | undefined) {
  if (amount == null || !Number.isFinite(amount)) return "Price unknown";
  if (!currency) return "Currency unknown";
  return new Intl.NumberFormat("en-GB", { style: "currency", currency }).format(amount);
}
function recordedWears(garment: Garment) {
  return garment.wearCount === 0
    ? "Never worn · no recorded wears"
    : `${garment.wearCount} recorded ${garment.wearCount === 1 ? "wear" : "wears"}`;
}
export function InsightPiece({
  garment,
  metric = "wear",
}: {
  garment: Garment;
  metric?: "wear" | "cost" | "purchase";
}) {
  return (
    <Link href={`/garments/${garment.id}`} className="insight-piece">
      <GarmentArt garment={garment} />
      <span className="insight-piece-copy">
        <strong>{garment.name}</strong>
        <span>
          {categoryNames[garment.category]} · {availability[garment.status]}
        </span>
        <span>
          {metric === "wear"
            ? recordedWears(garment)
            : metric === "purchase"
              ? `${money(garment.purchasePrice, garment.purchaseCurrency)} purchase price · never worn`
              : `${money(garment.costPerWear, garment.purchaseCurrency)} per recorded wear · ${recordedWears(garment)}`}
        </span>
      </span>
      <ChevronRight size={18} aria-hidden="true" />
    </Link>
  );
}
function PieceList({
  title,
  pieces,
  metric,
  empty,
}: {
  title: string;
  pieces: Garment[];
  metric?: "wear" | "cost" | "purchase";
  empty: string;
}) {
  return (
    <section className="insight-list-section" aria-label={title}>
      <h2>{title}</h2>
      {pieces.length ? (
        <ol className="insight-pieces">
          {pieces.map((garment) => (
            <li key={garment.id}>
              <InsightPiece garment={garment} metric={metric} />
            </li>
          ))}
        </ol>
      ) : (
        <p className="insight-empty-note">{empty}</p>
      )}
    </section>
  );
}
export function CalculationDetails({ explanations }: { explanations: string[] }) {
  return (
    <details className="insight-calculations">
      <summary>How these insights are calculated</summary>
      <ul>
        {explanations.map((explanation) => (
          <li key={explanation}>{explanation}</li>
        ))}
      </ul>
    </details>
  );
}
function Usage({ data }: { data: UsageInsights }) {
  return (
    <>
      <dl className="insight-stats">
        <div>
          <dt>Reviewed pieces</dt>
          <dd>{data.garmentCount}</dd>
        </div>
        <div>
          <dt>Garment wear occurrences</dt>
          <dd>{data.garmentWearOccurrences}</dd>
        </div>
        <div>
          <dt>Never worn</dt>
          <dd>{data.neverWornCount}</dd>
        </div>
      </dl>
      <p className="insight-context">
        Each piece in an outfit counts as one garment wear occurrence. “Never worn” means no wear
        has been recorded.
      </p>
      {data.unknownLastWornCount > 0 && (
        <p className="processing-notice">
          {data.unknownLastWornCount} worn{" "}
          {data.unknownLastWornCount === 1 ? "piece has" : "pieces have"} no known last-worn date.
        </p>
      )}
      <section className="insight-breakdown" aria-label="Pieces by category">
        <h2>Your wardrobe by category</h2>
        <ul>
          {categories
            .filter((category) => data.categories[category])
            .map((category) => (
              <li key={category}>
                <span>{categoryNames[category]}</span>
                <strong>{data.categories[category]}</strong>
                <span className="insight-category-bar" aria-hidden="true">
                  <span
                    style={{
                      width: `${((data.categories[category] ?? 0) / Math.max(data.garmentCount, 1)) * 100}%`,
                    }}
                  />
                </span>
              </li>
            ))}
        </ul>
      </section>
      <div className="insight-columns">
        <PieceList
          title="Most worn"
          pieces={data.mostWorn}
          empty="No wear has been recorded yet. Open a piece or an outfit to log a wear."
        />
        <PieceList
          title="Least worn"
          pieces={data.leastWorn}
          empty="Add and review a piece to begin building your wardrobe history."
        />
        <PieceList
          title="Never worn"
          pieces={data.neverWorn}
          empty={
            data.garmentCount === 0
              ? "No reviewed pieces in this view yet."
              : "Every reviewed piece in this view has a recorded wear."
          }
        />
      </div>
      <CalculationDetails explanations={data.explanations} />
    </>
  );
}
function Costs({
  data,
  requestedCurrency,
  choose,
}: {
  data: CostInsights;
  requestedCurrency: string;
  choose: (currency: string) => void;
}) {
  const group =
    data.currencies.find((item) => item.currency === requestedCurrency) ?? data.currencies[0];
  return (
    <>
      <p className="insight-context">
        Compare recorded prices within one currency. Pieces with no recorded wears show their
        purchase price separately.
      </p>
      {(data.missingPriceCount > 0 || data.missingCurrencyCount > 0) && (
        <p className="processing-notice">
          {data.missingPriceCount} {data.missingPriceCount === 1 ? "piece has" : "pieces have"} no
          purchase price. {data.missingCurrencyCount} priced{" "}
          {data.missingCurrencyCount === 1 ? "piece has" : "pieces have"} no recorded currency.
          These pieces are excluded from currency rankings.
        </p>
      )}
      {group ? (
        <>
          <label className="insight-currency">
            Recorded currency
            <select value={group.currency} onChange={(event) => choose(event.target.value)}>
              {data.currencies.map((item) => (
                <option key={item.currency} value={item.currency}>
                  {item.currency}
                </option>
              ))}
            </select>
          </label>
          <dl className="insight-stats">
            <div>
              <dt>Pieces priced in {group.currency}</dt>
              <dd>{group.pricedGarmentCount}</dd>
            </div>
            <div>
              <dt>Known purchase total · {group.currency}</dt>
              <dd>{money(group.knownPurchaseTotal, group.currency)}</dd>
            </div>
          </dl>
          <p className="insight-context">
            This total uses recorded purchase prices. Missing prices and other currencies are not
            included.
          </p>
          <div className="insight-columns">
            <PieceList
              title="Lowest cost per wear"
              pieces={group.lowestCostPerWear}
              metric="cost"
              empty="No priced pieces in this currency have a recorded wear yet."
            />
            <PieceList
              title="Highest cost per wear"
              pieces={group.highestCostPerWear}
              metric="cost"
              empty="Log a wear for a priced piece to start comparing cost per wear."
            />
            <PieceList
              title="Never worn purchases"
              pieces={group.neverWorn}
              metric="purchase"
              empty="Every priced piece in this currency has a recorded wear."
            />
          </div>
        </>
      ) : (
        <div className="empty-state">
          <h2>A little more context is needed.</h2>
          <p>Add a purchase price and currency to a piece to see its cost per wear.</p>
          <Link href="/catalogue" className="button button-secondary">
            Browse your pieces
          </Link>
        </div>
      )}
      <CalculationDetails explanations={data.explanations} />
    </>
  );
}
function Forgotten({ data }: { data: ForgottenInsights }) {
  return (
    <>
      <p className="insight-context">
        Pieces owned for at least {data.minimumOwnershipDays} days, with no recorded wear or no wear
        in the last {data.minimumDaysSinceWear} days. Purchase dates are used when known; otherwise
        we use the date added.
      </p>
      <p className="insight-result-count" role="status">
        Showing {data.items.length} of {data.eligibleCount} eligible pieces, ranked from{" "}
        {data.garmentCount} reviewed pieces.
      </p>
      {data.unknownLastWornCount > 0 && (
        <p className="processing-notice">
          {data.unknownLastWornCount} worn{" "}
          {data.unknownLastWornCount === 1 ? "piece is" : "pieces are"} excluded because the
          last-worn date is unknown.
        </p>
      )}
      {data.items.length ? (
        <ol className="forgotten-pieces">
          {data.items.map((item) => (
            <li key={item.garment.id}>
              <InsightPiece garment={item.garment} />
              <details>
                <summary>Why this appeared</summary>
                <ul>
                  {item.reasons.map((reason) => (
                    <li key={reason}>{reason}</li>
                  ))}
                </ul>
              </details>
            </li>
          ))}
        </ol>
      ) : (
        <div className="empty-state">
          <Sprout size={40} strokeWidth={1} aria-hidden="true" />
          <h2>
            {data.garmentCount === 0
              ? "No wardrobe history yet."
              : "Nothing forgotten in this view."}
          </h2>
          <p>
            {data.garmentCount === 0
              ? "Add and review a piece, then record wears to start discovering your wardrobe habits."
              : "New purchases and recently worn pieces stay out of this list. Keep recording wears to build a clearer picture."}
          </p>
          <Link href="/catalogue" className="button button-secondary">
            Browse your pieces
          </Link>
        </div>
      )}
      <CalculationDetails explanations={data.explanations} />
    </>
  );
}

export function WardrobeInsights() {
  const url = useSearchParams();
  const router = useRouter();
  const [draft, edit] = useOptimistic(
    url.toString(),
    (current, changes: Record<string, string>) => {
      const next = new URLSearchParams(current);
      for (const [key, value] of Object.entries(changes)) {
        if (value) next.set(key, value);
        else next.delete(key);
      }
      return next.toString();
    },
  );
  const params = new URLSearchParams(draft);
  const requestedView = params.get("view");
  const view: InsightView =
    requestedView === "costs" || requestedView === "usage" ? requestedView : "forgotten";
  const requestedSeason = params.get("season") ?? "";
  const season: InsightSeason | "" = Object.hasOwn(seasons, requestedSeason)
    ? (requestedSeason as InsightSeason)
    : "";
  const includeArchived = params.get("archived") === "true";
  const requestedLimit = Number(params.get("limit") ?? "12");
  const limit = [12, 24, 60, 100].includes(requestedLimit) ? requestedLimit : 12;
  const query = useInsights(view, season, includeArchived, limit);
  function update(changes: Record<string, string>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    startTransition(() => {
      edit(changes);
      router.push(`/discover/insights?${next}`, { scroll: false });
    });
  }
  return (
    <div className="page insights-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Your wardrobe, considered</p>
          <h1>A fresh look at what you own.</h1>
          <p>
            A little perspective on what gets worn, what gets overlooked, and the value in your
            everyday pieces.
          </p>
        </div>
        <Link href="/catalogue" className="button button-secondary">
          Browse pieces
        </Link>
      </div>
      <div className="insight-tabs" aria-label="Insight views">
        <Link href="/discover/duplicates" className="button button-quiet">
          Potential duplicates
        </Link>
        {(Object.entries(views) as [InsightView, string][]).map(([key, label]) => (
          <Button
            key={key}
            variant={view === key ? "secondary" : "quiet"}
            aria-pressed={view === key}
            onClick={() => update({ view: key === "forgotten" ? "" : key })}
          >
            {label}
          </Button>
        ))}
      </div>
      <div className="insight-toolbar">
        {view === "forgotten" ? (
          <label>
            Your current season
            <select value={season} onChange={(event) => update({ season: event.target.value })}>
              <option value="">No season selected</option>
              {Object.entries(seasons).map(([key, label]) => (
                <option key={key} value={key}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        ) : (
          <label className="insight-checkbox">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={(event) => update({ archived: event.target.checked ? "true" : "" })}
            />
            Include archived pieces
          </label>
        )}
        <label>
          Pieces per list
          <select
            value={limit}
            onChange={(event) =>
              update({ limit: event.target.value === "12" ? "" : event.target.value })
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
          {query.isFetching ? "Refreshing…" : "Refresh insights"}
        </Button>
      </div>
      {view === "forgotten" && !season && (
        <p className="insight-context">
          Choose the current season where you live to include season compatibility in this ranking.
        </p>
      )}
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : (
        query.data && (
          <>
            <p className="insight-as-of">
              Based on reviewed pieces and recorded history as of{" "}
              {new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeZone: "UTC" }).format(
                new Date(`${query.data.data.asOf}T12:00:00Z`),
              )}
              .
            </p>
            {query.data.view === "usage" ? (
              <Usage data={query.data.data} />
            ) : query.data.view === "costs" ? (
              <Costs
                data={query.data.data}
                requestedCurrency={params.get("currency") ?? ""}
                choose={(currency) => update({ currency })}
              />
            ) : (
              <Forgotten data={query.data.data} />
            )}
          </>
        )
      )}
    </div>
  );
}
