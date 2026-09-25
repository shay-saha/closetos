"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "@/features/garments/queries";
import { GarmentArt } from "@/features/garments/garment-art";
import { categories, categoryNames, type Garment } from "@/features/garments/types";
import { WearHistory, WearLog } from "@/features/wear/wear";
import { api } from "@/lib/api";
import { OutfitCanvas } from "./outfit-canvas";
import { moveItem, type Outfit, type OutfitInput, type OutfitItem } from "./types";

function StudioEditor({ initial, garmentId }: { initial?: Outfit; garmentId?: string }) {
  const router = useRouter();
  const client = useQueryClient();
  const [items, setItems] = useState<OutfitItem[]>(
    initial?.items ??
      (garmentId ? [{ garmentId, x: 50, y: 50, scale: 1, rotation: 0, zIndex: 0 }] : []),
  );
  const [version, setVersion] = useState(initial?.version);
  const [selected, select] = useState<string | undefined>(garmentId);
  const [category, setCategory] = useState("");
  const [dirty, setDirty] = useState(false);
  const drawer = useGarments(`processingStatus=READY${category ? `&category=${category}` : ""}`);
  const savedPieces = useQueries({
    queries: items.map((item) => ({
      queryKey: ["garment", item.garmentId],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        api<Garment>(`garments/${item.garmentId}`, { signal }),
    })),
  });
  const available = drawer.data?.pages.flatMap((page) => page.items) ?? [];
  const garments = new Map(
    [...available, ...savedPieces.flatMap((query) => (query.data ? [query.data] : []))].map(
      (garment) => [garment.id, garment],
    ),
  );
  function change(item: OutfitItem) {
    if (save.isPending) return;
    setItems((current) =>
      current.map((piece) => (piece.garmentId === item.garmentId ? item : piece)),
    );
    setDirty(true);
  }
  function add(id: string, x = 50, y = 50) {
    if (save.isPending) return;
    if (items.some((item) => item.garmentId === id)) {
      select(id);
      return;
    }
    if (items.length >= 50) return;
    setItems((current) => [
      ...current,
      moveItem(
        {
          garmentId: id,
          x: 50,
          y: 50,
          scale: 1,
          rotation: 0,
          zIndex: Math.min(1000, Math.max(-1, ...current.map((item) => item.zIndex)) + 1),
        },
        x,
        y,
      ),
    ]);
    select(id);
    setDirty(true);
  }
  const save = useMutation({
    mutationFn: (input: OutfitInput) =>
      api<Outfit>(initial ? `outfits/${initial.id}` : "outfits", {
        method: initial ? "PATCH" : "POST",
        body: JSON.stringify({ ...input, ...(initial ? { version } : {}) }),
      }),
    onSuccess: async (saved) => {
      client.setQueryData(["outfit", saved.id], saved);
      setVersion(saved.version);
      setDirty(false);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["outfits"] }),
        client.invalidateQueries({ queryKey: ["search", "works-with"] }),
      ]);
      if (!initial) router.replace(`/outfits/${saved.id}`);
    },
  });
  const duplicate = useMutation({
    mutationFn: () =>
      api<Outfit>(`outfits/${initial?.id}/duplicate`, {
        method: "POST",
        body: JSON.stringify({ version }),
      }),
    onSuccess: async (copy) => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["outfits"] }),
        client.invalidateQueries({ queryKey: ["search", "works-with"] }),
      ]);
      router.push(`/outfits/${copy.id}`);
    },
  });
  const remove = useMutation({
    mutationFn: () => api<void>(`outfits/${initial?.id}?version=${version}`, { method: "DELETE" }),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["outfits"] }),
        client.invalidateQueries({ queryKey: ["search", "works-with"] }),
      ]);
      router.push("/outfits");
    },
  });
  const active = items.find((item) => item.garmentId === selected);
  const outfitGarments = items.flatMap((item) =>
    garments.get(item.garmentId) ? [garments.get(item.garmentId)!] : [],
  );
  const complete =
    outfitGarments.some((piece) => piece.category === "DRESS") ||
    (outfitGarments.some((piece) => piece.category === "TOP") &&
      outfitGarments.some((piece) => piece.category === "BOTTOM"));
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A little room to play</p>
          <h1>Bring pieces together.</h1>
          <p>
            Drag from the drawer, or tap to add. Move selected pieces with arrow keys; hold Shift
            for larger steps.
          </p>
        </div>
        <Link href="/outfits" className="button button-secondary">
          Saved outfits →
        </Link>
      </div>
      <fieldset
        className="studio-workspace"
        disabled={save.isPending || duplicate.isPending || remove.isPending}
      >
        <legend className="sr-only">Outfit studio</legend>
        <aside className="studio-drawer" aria-label="Garment drawer">
          <h2>Your pieces</h2>
          <label className="field">
            Drawer category
            <select value={category} onChange={(event) => setCategory(event.target.value)}>
              <option value="">All categories</option>
              {categories.map((value) => (
                <option value={value} key={value}>
                  {categoryNames[value]}
                </option>
              ))}
            </select>
          </label>
          {drawer.isError ? (
            <ErrorState error={drawer.error} retry={drawer.refetch} />
          ) : (
            <div className="drawer-pieces">
              {available.map((garment) => (
                <button
                  type="button"
                  key={garment.id}
                  disabled={
                    items.length >= 50 && !items.some((item) => item.garmentId === garment.id)
                  }
                  className="drawer-piece"
                  draggable
                  onDragStart={(event) =>
                    event.dataTransfer.setData("application/closetos-garment", garment.id)
                  }
                  onClick={() => add(garment.id)}
                  aria-label={`Add ${garment.name} to outfit`}
                >
                  <GarmentArt garment={garment} />
                  <span>{garment.name}</span>
                </button>
              ))}
            </div>
          )}
          {drawer.isPending && <p role="status">Loading pieces…</p>}
          {!drawer.isPending && !available.length && (
            <p className="small">
              Confirmed pieces will appear here. Add or review a piece to begin.
            </p>
          )}
          {drawer.hasNextPage && (
            <Button
              variant="quiet"
              disabled={drawer.isFetchingNextPage}
              onClick={() => void drawer.fetchNextPage()}
            >
              More pieces
            </Button>
          )}
          <p className="small">{items.length} / 50 pieces on the canvas</p>
        </aside>
        <section className="studio-stage" aria-label="Outfit composition">
          <OutfitCanvas
            items={items}
            garments={garments}
            selected={selected}
            select={select}
            change={change}
            add={add}
          />
          {!!items.length && !complete && (
            <p className="small">
              This combination may need an upper and lower piece, or a dress. You can save it just
              as it is.
            </p>
          )}
          {outfitGarments.some((piece) => piece.status !== "AVAILABLE") && (
            <p className="small">
              Some pieces are currently in the laundry, packed, lent, or archived.
            </p>
          )}
          {savedPieces.some((query) => query.isError) && (
            <p role="alert">
              A piece could not be loaded. Try reloading, or remove it before saving.
            </p>
          )}
          {active && (
            <div className="canvas-controls" aria-label="Selected piece controls">
              <p>
                <strong>{garments.get(active.garmentId)?.name ?? "Selected piece"}</strong>
              </p>
              <div className="field-row">
                {(
                  [
                    ["scale", "Scale", 0.1, 3, 0.05],
                    ["rotation", "Rotation", -180, 180, 1],
                  ] as const
                ).map(([field, label, min, max, step]) => (
                  <label className="field" key={field}>
                    {label}
                    <input
                      type="range"
                      min={min}
                      max={max}
                      step={step}
                      value={active[field]}
                      onChange={(event) =>
                        change({ ...active, [field]: Number(event.target.value) })
                      }
                    />
                    <output>
                      {active[field]}
                      {field === "rotation" ? "°" : "×"}
                    </output>
                  </label>
                ))}
              </div>
              <div className="form-actions">
                <Button
                  variant="secondary"
                  onClick={() => {
                    const ordered = [...items]
                      .sort((a, b) => a.zIndex - b.zIndex)
                      .filter((item) => item.garmentId !== active.garmentId);
                    setItems([
                      ...ordered.map((item, index) => ({ ...item, zIndex: index })),
                      { ...active, zIndex: ordered.length },
                    ]);
                    setDirty(true);
                  }}
                >
                  Bring to front
                </Button>
                <Button
                  variant="quiet"
                  onClick={() => {
                    setItems(items.filter((item) => item.garmentId !== active.garmentId));
                    select(undefined);
                    setDirty(true);
                  }}
                >
                  Remove from canvas
                </Button>
              </div>
            </div>
          )}
          <form
            className="garment-form outfit-metadata"
            onChange={() => setDirty(true)}
            onSubmit={(event) => {
              event.preventDefault();
              const form = new FormData(event.currentTarget);
              save.mutate({
                name: String(form.get("name")).trim(),
                occasion: String(form.get("occasion")) || null,
                season: String(form.get("season")) || null,
                rating: form.get("rating") ? Number(form.get("rating")) : null,
                tags: String(form.get("tags"))
                  .split(",")
                  .map((tag) => tag.trim())
                  .filter(Boolean),
                notes: String(form.get("notes")) || null,
                archived: form.get("archived") === "on",
                items,
              });
            }}
          >
            <h2>A name for the feeling.</h2>
            <label>
              Outfit name
              <input name="name" required maxLength={160} defaultValue={initial?.name ?? ""} />
            </label>
            <div className="field-row">
              <label>
                Occasion
                <input name="occasion" maxLength={80} defaultValue={initial?.occasion ?? ""} />
              </label>
              <label>
                Season
                <input name="season" maxLength={60} defaultValue={initial?.season ?? ""} />
              </label>
            </div>
            <div className="field-row">
              <label>
                Rating
                <select name="rating" defaultValue={initial?.rating ?? ""}>
                  <option value="">Not rated</option>
                  {[1, 2, 3, 4, 5].map((rating) => (
                    <option value={rating} key={rating}>
                      {rating} / 5
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Outfit tags
                <input
                  name="tags"
                  defaultValue={initial?.tags.join(", ") ?? ""}
                  placeholder="Separate with commas"
                />
              </label>
            </div>
            <label>
              Outfit notes
              <textarea
                name="notes"
                maxLength={4000}
                rows={3}
                defaultValue={initial?.notes ?? ""}
              />
            </label>
            {initial && (
              <label className="checkbox-field">
                <input name="archived" type="checkbox" defaultChecked={initial.archived} />
                Archive this outfit
              </label>
            )}
            {save.error && (
              <p className="field-error" role="alert">
                {save.error.message}
              </p>
            )}
            {save.isSuccess && <p role="status">Your outfit is saved.</p>}
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? "Saving outfit…" : "Save outfit"}
            </Button>
          </form>
          {initial && (
            <>
              <div className="form-actions">
                <Button
                  variant="secondary"
                  disabled={dirty || duplicate.isPending}
                  onClick={() => duplicate.mutate()}
                >
                  Duplicate outfit
                </Button>
                <Dialog.Root>
                  <Dialog.Trigger className="button button-danger">Delete outfit</Dialog.Trigger>
                  <Dialog.Portal>
                    <Dialog.Backdrop className="dialog-backdrop" />
                    <Dialog.Popup className="dialog-popup">
                      <Dialog.Title>Delete this outfit?</Dialog.Title>
                      <Dialog.Description>
                        The arrangement will be removed. Your garments and wear history will be
                        kept.
                      </Dialog.Description>
                      {remove.error && <p role="alert">{remove.error.message}</p>}
                      <div className="form-actions">
                        <Dialog.Close className="button button-secondary">Keep outfit</Dialog.Close>
                        <Button
                          variant="danger"
                          disabled={remove.isPending}
                          onClick={() => remove.mutate()}
                        >
                          Permanently delete outfit
                        </Button>
                      </div>
                    </Dialog.Popup>
                  </Dialog.Portal>
                </Dialog.Root>
              </div>
              {duplicate.error && <p role="alert">{duplicate.error.message}</p>}
              {dirty ? (
                <p className="small">
                  Save your changes before logging wear or duplicating this outfit.
                </p>
              ) : (
                items.length > 0 && <WearLog target={{ outfitId: initial.id, version: version! }} />
              )}
              <WearHistory outfitId={initial.id} />
            </>
          )}
        </section>
      </fieldset>
    </div>
  );
}

export function OutfitStudio({ id, garmentId }: { id?: string; garmentId?: string }) {
  const query = useQuery({
    queryKey: ["outfit", id],
    enabled: !!id,
    queryFn: ({ signal }) => api<Outfit>(`outfits/${id}`, { signal }),
  });
  if (id && query.isPending)
    return (
      <div className="page">
        <LoadingState />
      </div>
    );
  if (id && query.isError) return <ErrorState error={query.error} retry={query.refetch} />;
  return <StudioEditor key={id ?? garmentId ?? "new"} initial={query.data} garmentId={garmentId} />;
}
