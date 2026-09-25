"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import { Capsule } from "./capsule";
import { ConstraintPicker, ReplacementPicker } from "./piece-picker";
import { TripForm } from "./trip-form";
import { refreshPacking, usePackingList } from "./queries";
import { initialTrip, replacePackedPiece, tripInput, tripSchema, type PackingList } from "./types";

export function PackingEditor({ id }: { id?: string }) {
  if (!id) return <PackingWorkspace />;
  return <SavedPackingEditor id={id} />;
}
function SavedPackingEditor({ id }: { id: string }) {
  const query = usePackingList(id);
  if (query.isPending)
    return (
      <div className="page">
        <LoadingState />
      </div>
    );
  if (query.isError)
    return (
      <div className="page">
        <ErrorState error={query.error} retry={query.refetch} />
      </div>
    );
  return (
    <PackingWorkspace
      key={id}
      initial={query.data}
      reload={async () => {
        const response = await query.refetch();
        if (response.error) throw response.error;
        return response.data!;
      }}
    />
  );
}
type Action =
  | { type: "save" }
  | { type: "optimise" }
  | { type: "pack"; id: string; packed: boolean }
  | { type: "swap"; piece: Garment }
  | { type: "delete" };

export function PackingWorkspace({
  initial,
  reload,
}: {
  initial?: PackingList;
  reload?: () => Promise<PackingList>;
}) {
  const router = useRouter();
  const replacementId = useId();
  const client = useQueryClient();
  const [saved, setSaved] = useState(initial);
  const [trip, setTrip] = useState(() => (initial ? tripInput(initial) : initialTrip()));
  const [issues, setIssues] = useState<string[]>([]);
  const [notice, setNotice] = useState("");
  const [swap, setSwap] = useState("");
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [editingTrip, setEditingTrip] = useState(!initial);
  const [reloadError, setReloadError] = useState<Error | null>(null);
  const [reloading, setReloading] = useState(false);
  const dirty = !saved || JSON.stringify(trip) !== JSON.stringify(tripInput(saved));
  const packed = saved?.items.some((item) => item.status === "PACKED") ?? false;
  const changedElsewhere = initial && saved && initial.version > saved.version;
  const mutation = useMutation({
    mutationFn: async (action: Action): Promise<PackingList | undefined> => {
      setNotice("");
      setIssues([]);
      setReloadError(null);
      if (action.type === "save") {
        const parsed = tripSchema.safeParse(trip);
        if (!parsed.success) {
          setIssues([...new Set(parsed.error.issues.map((issue) => issue.message))]);
          throw new Error("Review the trip details below before saving.");
        }
        return saved
          ? api<PackingList>(`packing-lists/${saved.id}`, {
              method: "PATCH",
              body: JSON.stringify({ ...parsed.data, version: saved.version }),
            })
          : api<PackingList>("packing-lists", {
              method: "POST",
              body: JSON.stringify(parsed.data),
            });
      }
      if (!saved) throw new Error("Save the trip first.");
      if (action.type === "delete") {
        await api<void>(`packing-lists/${saved.id}?version=${saved.version}`, { method: "DELETE" });
        return;
      }
      if (action.type === "optimise")
        return api<PackingList>(`packing-lists/${saved.id}/optimise`, {
          method: "POST",
          body: JSON.stringify({ version: saved.version }),
        });
      if (action.type === "pack") {
        const piece = saved.items.find((item) => item.garment.id === action.id)?.garment;
        if (!piece) throw new Error("Reload this capsule to check its current pieces.");
        return api<PackingList>(`packing-lists/${saved.id}/items/${action.id}`, {
          method: "PATCH",
          body: JSON.stringify({
            version: saved.version,
            garmentVersion: piece.version,
            packed: action.packed,
          }),
        });
      }
      if (!saved.plan || !swap) throw new Error("Choose the piece you want to replace.");
      return api<PackingList>(`packing-lists/${saved.id}/manual`, {
        method: "POST",
        body: JSON.stringify({
          version: saved.version,
          plan: replacePackedPiece(saved.plan, swap, action.piece.id),
        }),
      });
    },
    onSuccess: async (result, action) => {
      if (!result) {
        await client.invalidateQueries({ queryKey: ["packing"] });
        router.replace("/packing");
        return;
      }
      const wasNew = !saved;
      setSaved(result);
      setTrip(tripInput(result));
      if (action.type === "save") setEditingTrip(false);
      setSwap("");
      setNotice(
        {
          save: "Trip saved.",
          optimise:
            result.plan?.status === "INFEASIBLE"
              ? "Your trip needs different constraints. Review the explanation below."
              : result.plan?.status === "TIME_LIMIT"
                ? "No solution confirmed yet. Review the result below."
                : "Capsule generated.",
          pack: "Packing status saved.",
          swap: "Replacement checked and saved.",
          delete: "",
        }[action.type],
      );
      await refreshPacking(client, result);
      if (wasNew) router.replace(`/packing/${result.id}`);
    },
  });
  const busy = mutation.isPending || reloading;
  async function reloadCurrent() {
    if (!reload) return;
    setReloading(true);
    try {
      const current = await reload();
      setSaved(current);
      setTrip(tripInput(current));
      setSwap("");
      setIssues([]);
      setReloadError(null);
      mutation.reset();
      setNotice("Current trip loaded. Your unsaved edits have been discarded.");
    } catch (error) {
      setReloadError(
        error instanceof Error ? error : new Error("The current trip could not be loaded."),
      );
    } finally {
      setReloading(false);
    }
  }
  const conflict = mutation.error instanceof ApiError && mutation.error.status === 409;
  return (
    <div className="page packing-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A little less to carry</p>
          <h1>{saved ? saved.name : "Pack with intention."}</h1>
          <p>Define your trip, then find a compact wardrobe for every day.</p>
        </div>
        <Link href="/packing" className="button button-secondary">
          All trips
        </Link>
      </div>
      {notice && (
        <p className="packing-notice" role="status">
          {notice}
        </p>
      )}
      {(conflict || changedElsewhere) && (
        <div className="packing-warning" role="alert">
          <p>
            This trip or its wardrobe changed. Reload it before trying again. Reloading discards
            unsaved edits.
          </p>
          <Button variant="secondary" disabled={busy} onClick={() => void reloadCurrent()}>
            Reload current trip
          </Button>
        </div>
      )}
      {mutation.isError && !conflict && <ErrorState error={mutation.error} />}
      {reloadError && <ErrorState error={reloadError} retry={reloadCurrent} />}
      {issues.length > 0 && (
        <ul className="packing-warnings" aria-label="Trip validation issues">
          {issues.map((issue) => (
            <li key={issue}>{issue}</li>
          ))}
        </ul>
      )}
      {packed && (
        <p className="packing-warning">
          Unpack this list’s pieces before editing constraints, regenerating, swapping, or deleting
          the trip.
        </p>
      )}
      <details
        className="packing-settings"
        open={editingTrip}
        onToggle={(event) => setEditingTrip(event.currentTarget.open)}
      >
        <summary>Trip details and constraints</summary>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            mutation.mutate({ type: "save" });
          }}
        >
          <TripForm trip={trip} onChange={setTrip} disabled={busy || packed} />
          <ConstraintPicker
            constraints={trip.constraints}
            references={saved?.constraintPieces ?? []}
            onChange={(constraints) => setTrip({ ...trip, constraints })}
            disabled={busy || packed}
          />
          <div className="form-actions">
            <Button type="submit" disabled={busy || packed || !dirty}>
              {mutation.isPending && mutation.variables?.type === "save"
                ? "Saving trip…"
                : saved
                  ? "Save trip changes"
                  : "Save trip"}
            </Button>
          </div>
        </form>
      </details>
      {saved && dirty && (
        <p className="small">Save your changes before generating, swapping, or packing.</p>
      )}
      {saved && (
        <>
          <div className="form-actions packing-generate">
            <Button
              variant="secondary"
              disabled={busy || dirty || packed || conflict}
              onClick={() => mutation.mutate({ type: "optimise" })}
            >
              {mutation.isPending && mutation.variables?.type === "optimise"
                ? "Finding a capsule…"
                : saved.plan
                  ? "Regenerate capsule"
                  : "Generate capsule"}
            </Button>
          </div>
          <Capsule
            list={saved}
            disabled={busy || dirty || conflict}
            onPack={(id, packed) => mutation.mutate({ type: "pack", id, packed })}
          />
          {saved.plan?.selectedGarments.length && !saved.stale ? (
            <section className="packing-section" aria-labelledby="packing-swap-heading">
              <h2 id="packing-swap-heading">Make it yours.</h2>
              <div className="packing-search">
                <label htmlFor={replacementId}>Piece to replace</label>
                <select
                  id={replacementId}
                  disabled={busy || dirty || packed || conflict}
                  value={swap}
                  onChange={(event) => {
                    mutation.reset();
                    setSwap(event.target.value);
                  }}
                >
                  <option value="">Choose a capsule piece</option>
                  {saved.items.map(({ garment }) => (
                    <option key={garment.id} value={garment.id}>
                      {garment.name}
                    </option>
                  ))}
                </select>
              </div>
              {swap && (
                <ReplacementPicker
                  selectedIds={saved.plan.selectedGarments}
                  disabled={busy || dirty || packed || conflict}
                  onChoose={(piece) => mutation.mutate({ type: "swap", piece })}
                />
              )}
            </section>
          ) : null}
          <section className="packing-section">
            <Dialog.Root open={deleteOpen} onOpenChange={setDeleteOpen}>
              <Dialog.Trigger className="button button-danger" disabled={busy || packed}>
                Delete trip
              </Dialog.Trigger>
              <Dialog.Portal>
                <Dialog.Backdrop className="dialog-backdrop" />
                <Dialog.Popup className="dialog-popup">
                  <Dialog.Title>Delete this trip?</Dialog.Title>
                  <Dialog.Description>
                    The saved trip and capsule will be removed. Your wardrobe pieces are kept.
                  </Dialog.Description>
                  <div className="form-actions">
                    <Dialog.Close className="button button-secondary">Keep trip</Dialog.Close>
                    <Button
                      variant="danger"
                      disabled={busy}
                      onClick={() => {
                        setDeleteOpen(false);
                        mutation.mutate({ type: "delete" });
                      }}
                    >
                      Delete trip permanently
                    </Button>
                  </div>
                </Dialog.Popup>
              </Dialog.Portal>
            </Dialog.Root>
          </section>
        </>
      )}
    </div>
  );
}
