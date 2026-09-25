"use client";

import { useDeferredValue, useState } from "react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "@/features/garments/queries";
import { GarmentArt } from "@/features/garments/garment-art";
import { categoryNames, type Garment } from "@/features/garments/types";
import type { ConstraintPiece, Trip } from "./types";

type Props = {
  constraints: Trip["constraints"];
  references: ConstraintPiece[];
  onChange: (constraints: Trip["constraints"]) => void;
  disabled: boolean;
};
function availability(piece: Pick<Garment, "status" | "processingStatus">) {
  if (piece.processingStatus !== "READY") return "Awaiting review or processing";
  return {
    AVAILABLE: "Available",
    LAUNDRY: "In the laundry",
    PACKED: "Packed",
    LENT: "Lent",
    ARCHIVED: "Archived",
  }[piece.status];
}
export function ConstraintPicker({ constraints, references, onChange, disabled }: Props) {
  const [search, setSearch] = useState("");
  const deferred = useDeferredValue(search);
  const query = useGarments(new URLSearchParams({ q: deferred }).toString());
  const pieces = query.data?.pages.flatMap((page) => page.items) ?? [];
  const names = new Map(references.map((piece) => [piece.id, piece.name ?? "Removed piece"]));
  for (const piece of pieces) names.set(piece.id, piece.name);
  function toggle(id: string, group: "requiredGarments" | "excludedGarments", checked: boolean) {
    const other = group === "requiredGarments" ? "excludedGarments" : "requiredGarments";
    onChange({
      ...constraints,
      [group]: checked
        ? [...constraints[group], id]
        : constraints[group].filter((entry) => entry !== id),
      [other]: checked ? constraints[other].filter((entry) => entry !== id) : constraints[other],
    });
  }
  return (
    <section className="packing-section" aria-labelledby="packing-constraints-heading">
      <h2 id="packing-constraints-heading">Bring it. Leave it.</h2>
      <p>
        Required pieces must fit the trip’s constraints. Unavailable or unreviewed required pieces
        will make the request infeasible. Selecting one choice clears the other.
      </p>
      <fieldset disabled={disabled} className="packing-picker">
        <legend className="sr-only">Required and excluded pieces</legend>
        {(["requiredGarments", "excludedGarments"] as const).map((group) => (
          <div key={group} className="packing-selection">
            <h3>
              {group === "requiredGarments" ? "Required" : "Excluded"} · {constraints[group].length}
            </h3>
            {constraints[group].length > 0 && (
              <ul>
                {constraints[group].map((id, index) => (
                  <li key={id}>
                    <span>{names.get(id) ?? "Saved piece"}</span>
                    <Button
                      variant="quiet"
                      aria-label={`Remove ${group === "requiredGarments" ? "required" : "excluded"} piece ${index + 1}: ${names.get(id) ?? "Saved piece"}`}
                      onClick={() => toggle(id, group, false)}
                    >
                      Remove
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
        <label className="packing-search">
          Find pieces
          <input
            type="search"
            maxLength={160}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={query.refetch} />
        ) : pieces.length === 0 ? (
          <p>No pieces match this search.</p>
        ) : (
          <ul className="packing-picker-pieces">
            {pieces.map((piece) => (
              <li key={piece.id}>
                <GarmentArt garment={piece} />
                <div>
                  <strong>{piece.name}</strong>
                  <span>
                    {categoryNames[piece.category]} · {availability(piece)}
                  </span>
                </div>
                <div className="packing-checkboxes">
                  <label>
                    <input
                      type="checkbox"
                      checked={constraints.requiredGarments.includes(piece.id)}
                      disabled={
                        !constraints.requiredGarments.includes(piece.id) &&
                        constraints.requiredGarments.length >= 100
                      }
                      onChange={(event) =>
                        toggle(piece.id, "requiredGarments", event.target.checked)
                      }
                    />
                    Require {piece.name}
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={constraints.excludedGarments.includes(piece.id)}
                      disabled={
                        !constraints.excludedGarments.includes(piece.id) &&
                        constraints.excludedGarments.length >= 2000
                      }
                      onChange={(event) =>
                        toggle(piece.id, "excludedGarments", event.target.checked)
                      }
                    />
                    Exclude {piece.name}
                  </label>
                </div>
              </li>
            ))}
          </ul>
        )}
        {query.hasNextPage && (
          <Button
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            More pieces
          </Button>
        )}
      </fieldset>
    </section>
  );
}

export function ReplacementPicker({
  selectedIds,
  disabled,
  onChoose,
}: {
  selectedIds: string[];
  disabled: boolean;
  onChoose: (piece: Garment) => void;
}) {
  const [search, setSearch] = useState("");
  const deferred = useDeferredValue(search);
  const query = useGarments(new URLSearchParams({ q: deferred, status: "AVAILABLE" }).toString());
  const pieces =
    query.data?.pages
      .flatMap((page) => page.items)
      .filter((piece) => piece.processingStatus === "READY" && !selectedIds.includes(piece.id)) ??
    [];
  return (
    <fieldset disabled={disabled} className="packing-picker">
      <legend>Choose a replacement</legend>
      <p>
        Each swap replaces that piece in every suggested outfit. The complete capsule is checked
        against all trip constraints before it is saved.
      </p>
      <label className="packing-search">
        Find a replacement
        <input
          type="search"
          maxLength={160}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </label>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : (
        <ul className="packing-picker-pieces">
          {pieces.map((piece) => (
            <li key={piece.id}>
              <GarmentArt garment={piece} />
              <div>
                <strong>{piece.name}</strong>
                <span>{categoryNames[piece.category]}</span>
              </div>
              <Button
                variant="secondary"
                aria-label={`Choose ${piece.name} as replacement`}
                onClick={() => onChoose(piece)}
              >
                Choose this piece
              </Button>
            </li>
          ))}
        </ul>
      )}
      {!query.isPending && !query.isError && !pieces.length && (
        <p>
          No reviewed, available replacements on this page. Try another search or load more pieces.
        </p>
      )}
      {query.hasNextPage && (
        <Button
          variant="secondary"
          disabled={query.isFetchingNextPage}
          onClick={() => void query.fetchNextPage()}
        >
          More replacements
        </Button>
      )}
    </fieldset>
  );
}
