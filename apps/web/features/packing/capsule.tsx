"use client";

import Link from "next/link";
import { Button } from "@/components/ui/button";
import { GarmentArt } from "@/features/garments/garment-art";
import type { PackingList } from "./types";

export const planNames = {
  OPTIMAL: "Optimised capsule",
  FEASIBLE: "Valid capsule",
  INFEASIBLE: "These constraints cannot be met",
  TIME_LIMIT: "More time is needed",
};

export function Capsule({
  list,
  disabled,
  onPack,
}: {
  list: PackingList;
  disabled: boolean;
  onPack: (id: string, packed: boolean) => void;
}) {
  const garments = new Map(list.items.map((item) => [item.garment.id, item.garment]));
  const plan = list.plan;
  if (!plan)
    return (
      <section className="packing-section">
        <h2>Your capsule starts here.</h2>
        <p>Save your trip, then generate a compact set of pieces that covers your occasions.</p>
      </section>
    );
  const valid = plan.status === "OPTIMAL" || plan.status === "FEASIBLE";
  const packedCount = list.items.filter((item) => item.status === "PACKED").length;
  return (
    <section className="packing-section" aria-labelledby="packing-capsule-heading">
      <h2 id="packing-capsule-heading">
        {list.stale
          ? "This capsule needs a review"
          : list.manualOverride
            ? "Your adjusted capsule"
            : planNames[plan.status]}
      </h2>
      {list.stale && (
        <p role="status">
          Your wardrobe has changed since this capsule was created. You can unpack its pieces, then
          regenerate it before packing more.
        </p>
      )}
      {plan.status === "TIME_LIMIT" && (
        <p>
          No solution was confirmed within the time limit. This does not establish that your trip is
          infeasible. Try again or simplify your constraints.
        </p>
      )}
      {plan.warnings.length > 0 && (
        <ul className="packing-warnings">
          {plan.warnings.map((warning, index) => (
            <li key={`${warning.code}:${index}`}>{warning.detail}</li>
          ))}
        </ul>
      )}
      {list.explanations.length > 0 && (
        <details className="packing-explanations">
          <summary>Why this capsule?</summary>
          <ul>
            {list.explanations.map((explanation) => (
              <li key={explanation}>{explanation}</li>
            ))}
          </ul>
        </details>
      )}
      {valid && (
        <>
          <p className="packing-count">
            {list.items.length} unique pieces · {packedCount} packed · {plan.outfits.length}{" "}
            scheduled outfits
          </p>
          <ul className="packing-capsule-pieces">
            {list.items.map(({ garment, status }) => (
              <li key={garment.id}>
                <Link href={`/garments/${garment.id}`}>
                  <GarmentArt garment={garment} />
                  <strong>{garment.name}</strong>
                </Link>
                <span>{status === "PACKED" ? "Packed" : "To pack"}</span>
                <Button
                  variant={status === "PACKED" ? "quiet" : "secondary"}
                  disabled={disabled || (list.stale && status !== "PACKED")}
                  aria-label={`${status === "PACKED" ? "Unpack" : "Mark packed"}: ${garment.name}`}
                  onClick={() => onPack(garment.id, status !== "PACKED")}
                >
                  {status === "PACKED" ? "Unpack" : "Mark packed"}
                </Button>
              </li>
            ))}
          </ul>
          <h3>Outfits for your trip</h3>
          <ol className="packing-outfits">
            {plan.outfits.map((outfit) => {
              const demand = list.schedule.find((entry) => entry.index === outfit.demandIndex);
              return (
                <li key={outfit.demandIndex}>
                  <h4>
                    {demand
                      ? `${demand.name} · ${demand.date}`
                      : `Scheduled outfit ${outfit.demandIndex + 1}`}
                  </h4>
                  {demand?.formality && (
                    <p className="small">Recorded formality: {demand.formality}</p>
                  )}
                  <ul>
                    {outfit.garmentIds.map((id) => {
                      const garment = garments.get(id);
                      return (
                        <li key={id}>
                          {garment ? (
                            <Link href={`/garments/${id}`}>
                              <GarmentArt garment={garment} />
                              <span>{garment.name}</span>
                            </Link>
                          ) : (
                            <span>Removed piece · regenerate this capsule</span>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                </li>
              );
            })}
          </ol>
        </>
      )}
    </section>
  );
}
