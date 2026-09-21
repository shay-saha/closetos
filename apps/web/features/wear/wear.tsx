"use client";

import { useRef } from "react";
import Link from "next/link";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/feedback";
import { api } from "@/lib/api";

type WearEntry = {
  id: string;
  outfitId: string | null;
  outfitName: string | null;
  wornOn: string;
  notes: string | null;
  context: string | null;
  garmentIds: string[];
};
type WearPage = { items: WearEntry[]; nextCursor: string | null };
type Target =
  | { garmentId: string; outfitId?: never; version?: never }
  | { outfitId: string; version: number; garmentId?: never };

function today() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function WearLog({ target }: { target: Target }) {
  const client = useQueryClient();
  const submission = useRef<{ body: string; key: string }>(undefined);
  const save = useMutation({
    mutationFn: (form: HTMLFormElement) => {
      const values = new FormData(form);
      const body = JSON.stringify({
        wornOn: values.get("wornOn"),
        notes: values.get("notes") || null,
        context: values.get("context") || null,
        ...(target.outfitId ? { version: target.version } : { garmentIds: [target.garmentId] }),
      });
      if (submission.current?.body !== body)
        submission.current = { body, key: crypto.randomUUID() };
      return api<WearEntry>(target.outfitId ? `outfits/${target.outfitId}/wear` : "wear-events", {
        method: "POST",
        body,
        headers: { "Idempotency-Key": submission.current.key },
      });
    },
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["wear-events"] }),
        client.invalidateQueries({ queryKey: ["garment"] }),
        client.invalidateQueries({ queryKey: ["garments"] }),
      ]);
    },
  });
  if (save.isSuccess)
    return (
      <div className="processing-notice">
        <p role="status">
          Wear recorded for {save.data.garmentIds.length}{" "}
          {save.data.garmentIds.length === 1 ? "piece" : "pieces"}.
        </p>
        <Button
          variant="quiet"
          onClick={() => {
            submission.current = undefined;
            save.reset();
          }}
        >
          Log another wear
        </Button>
      </div>
    );
  return (
    <form
      className="garment-form wear-form"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate(event.currentTarget);
      }}
    >
      <h2>Give it a day out.</h2>
      <div className="field-row">
        <label>
          Worn on
          <input type="date" name="wornOn" required defaultValue={today()} max={today()} />
        </label>
        <label>
          Context
          <input name="context" maxLength={120} placeholder="Work, dinner, a quiet Sunday…" />
        </label>
      </div>
      <label>
        Wear notes
        <textarea name="notes" rows={2} maxLength={4000} />
      </label>
      {save.error && (
        <p role="alert" className="field-error">
          {save.error.message}
        </p>
      )}
      <Button type="submit" disabled={save.isPending}>
        {save.isPending ? "Recording wear…" : "Mark worn"}
      </Button>
    </form>
  );
}

export function WearHistory({ garmentId, outfitId }: { garmentId?: string; outfitId?: string }) {
  const client = useQueryClient();
  const filter = new URLSearchParams();
  if (garmentId) filter.set("garmentId", garmentId);
  if (outfitId) filter.set("outfitId", outfitId);
  const scope = filter.toString();
  const query = useInfiniteQuery({
    queryKey: ["wear-events", scope],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams(scope);
      if (pageParam) params.set("cursor", pageParam);
      return api<WearPage>(`wear-events?${params}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
  });
  const remove = useMutation({
    mutationFn: (id: string) => api<void>(`wear-events/${id}`, { method: "DELETE" }),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["wear-events"] }),
        client.invalidateQueries({ queryKey: ["garment"] }),
        client.invalidateQueries({ queryKey: ["garments"] }),
      ]);
    },
  });
  if (query.isError) return <ErrorState error={query.error} retry={query.refetch} />;
  const entries = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <section className="wear-history" aria-label="Wear history">
      <h2>A history of wearing.</h2>
      {query.isPending ? (
        <p role="status">Loading wear history…</p>
      ) : !entries.length ? (
        <p className="small">Your first wear will start the story.</p>
      ) : (
        <ul>
          {entries.map((entry) => (
            <li key={entry.id}>
              <div>
                <time dateTime={entry.wornOn}>
                  {new Date(entry.wornOn).toLocaleDateString("en-GB", { timeZone: "UTC" })}
                </time>
                {entry.context && <span> · {entry.context}</span>}
                {entry.outfitName && (
                  <p className="small">
                    {entry.outfitId ? (
                      <Link href={`/outfits/${entry.outfitId}`}>{entry.outfitName}</Link>
                    ) : (
                      entry.outfitName
                    )}
                  </p>
                )}
                {entry.notes && <p>{entry.notes}</p>}
              </div>
              <Dialog.Root>
                <Dialog.Trigger
                  className="button button-quiet"
                  aria-label={`Remove wear from ${entry.wornOn}`}
                >
                  Remove
                </Dialog.Trigger>
                <Dialog.Portal>
                  <Dialog.Backdrop className="dialog-backdrop" />
                  <Dialog.Popup className="dialog-popup">
                    <Dialog.Title>Remove this wear entry?</Dialog.Title>
                    <Dialog.Description>
                      The wear count and last-worn date will be recalculated for every included
                      piece.
                    </Dialog.Description>
                    {remove.error && <p role="alert">{remove.error.message}</p>}
                    <div className="form-actions">
                      <Dialog.Close className="button button-secondary">Keep entry</Dialog.Close>
                      <Button
                        variant="danger"
                        disabled={remove.isPending}
                        onClick={() => remove.mutate(entry.id)}
                      >
                        Remove wear entry
                      </Button>
                    </div>
                  </Dialog.Popup>
                </Dialog.Portal>
              </Dialog.Root>
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
          Earlier wears
        </Button>
      )}
    </section>
  );
}
