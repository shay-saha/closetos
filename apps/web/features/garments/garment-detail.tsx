"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { api } from "@/lib/api";
import { useGarment } from "./queries";
import { GarmentArt } from "./garment-art";
import { GarmentForm } from "./garment-form";
import { categoryNames, type Garment, type GarmentMetadata } from "./types";
import { GarmentReview } from "@/features/review/garment-review";
import { useSuggestions } from "@/features/review/suggestions";
import { WearHistory, WearLog } from "@/features/wear/wear";

export function GarmentDetail({ id }: { id: string }) {
  const query = useGarment(id);
  const client = useQueryClient();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const suggestions = useSuggestions(id);
  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["garments"] }),
      client.invalidateQueries({ queryKey: ["search"] }),
      client.invalidateQueries({ queryKey: ["garment", id] }),
    ]);
  };
  const save = useMutation({
    mutationFn: (metadata: GarmentMetadata) =>
      api<Garment>(`garments/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ ...metadata, version: query.data?.version }),
      }),
    onSuccess: async () => {
      await refresh();
      setEditing(false);
    },
  });
  const availability = useMutation({
    mutationFn: (status: Garment["status"]) =>
      api<Garment>(`garments/${id}/status`, {
        method: "POST",
        body: JSON.stringify({ status, version: query.data?.version }),
      }),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () =>
      api<void>(`garments/${id}?version=${query.data?.version}`, { method: "DELETE" }),
    onSuccess: async () => {
      await refresh();
      router.push("/catalogue");
    },
  });
  if (query.isPending)
    return (
      <div className="page">
        <LoadingState />
      </div>
    );
  if (query.isError) return <ErrorState error={query.error} retry={query.refetch} />;
  const garment = query.data;
  return (
    <div className="page">
      <Link className="button button-quiet" href="/catalogue">
        ← Your collection
      </Link>
      <div className="detail-grid">
        <GarmentArt garment={garment} size="display" />
        {reviewing ? (
          <GarmentReview
            garment={garment}
            completed={() => setReviewing(false)}
            cancel={() => setReviewing(false)}
          />
        ) : editing ? (
          <GarmentForm
            initial={garment}
            submit={save.mutate}
            pending={save.isPending}
            error={save.error}
            cancel={() => setEditing(false)}
          />
        ) : (
          <div className="detail-copy">
            <p className="eyebrow">{categoryNames[garment.category]}</p>
            <h1>{garment.name}</h1>
            {garment.processingStatus === "READY_FOR_REVIEW" && (
              <p className="processing-notice" role="status">
                Your photograph is ready. Review the details to complete your piece.
              </p>
            )}
            {["AWAITING_UPLOAD", "UPLOADED", "PROCESSING_MEDIA", "ANALYSING"].includes(
              garment.processingStatus,
            ) && (
              <p className="processing-notice" role="status">
                Your photograph is being prepared. You can add the details while we work.
              </p>
            )}
            {garment.processingStatus === "FAILED" && (
              <p className="processing-notice" role="status">
                This photograph could not be processed. Retry from the upload queue, or add the
                details yourself.
              </p>
            )}
            <p className="small">
              {[garment.brand, garment.primaryColourName, garment.material]
                .filter(Boolean)
                .join(" · ")}
            </p>
            <dl className="detail-metrics">
              <div>
                <dt>Times worn</dt>
                <dd>{garment.wearCount}</dd>
              </div>
              <div>
                <dt>{garment.wearCount ? "Cost per wear" : "Purchase price · never worn"}</dt>
                <dd>
                  {garment.costPerWear == null
                    ? "Unknown"
                    : new Intl.NumberFormat("en-GB", {
                        style: "currency",
                        currency: garment.purchaseCurrency ?? "GBP",
                      }).format(garment.costPerWear)}
                </dd>
              </div>
              <div>
                <dt>Last worn</dt>
                <dd>
                  {garment.lastWornAt
                    ? new Date(garment.lastWornAt).toLocaleDateString("en-GB")
                    : "Not yet"}
                </dd>
              </div>
            </dl>
            <dl className="detail-properties">
              {[
                ["Size", garment.sizeLabel],
                ["Formality", garment.formality],
                ["Seasons", garment.seasonTags?.join(", ")],
                ["Occasions", garment.occasionTags?.join(", ")],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value || "Not specified"}</dd>
                </div>
              ))}
            </dl>
            {garment.notes && (
              <p style={{ whiteSpace: "pre-wrap", marginTop: "2rem" }}>{garment.notes}</p>
            )}
            <label className="field" style={{ marginTop: "2rem" }}>
              Availability
              <select
                value={garment.status}
                disabled={availability.isPending}
                onChange={(event) => availability.mutate(event.target.value as Garment["status"])}
              >
                {["AVAILABLE", "LAUNDRY", "PACKED", "LENT", "ARCHIVED"].map((status) => (
                  <option key={status} value={status}>
                    {status.toLowerCase()}
                  </option>
                ))}
              </select>
            </label>
            {availability.error && <p role="alert">{availability.error.message}</p>}
            <div className="form-actions">
              {(garment.processingStatus === "READY_FOR_REVIEW" ||
                suggestions.data?.some((item) => item.status === "PENDING")) && (
                <Button onClick={() => setReviewing(true)}>Review details</Button>
              )}
              <Button onClick={() => setEditing(true)}>Edit piece</Button>
              {garment.processingStatus === "READY" && (
                <Link className="button button-secondary" href={`/studio?garment=${id}`}>
                  Add to outfit
                </Link>
              )}
              {garment.processingStatus === "READY" && (
                <Link
                  className="button button-secondary"
                  href={`/search?similarToGarmentId=${id}&mode=SEMANTIC`}
                >
                  Find similar pieces
                </Link>
              )}
              {garment.processingStatus === "READY" && garment.status === "AVAILABLE" && (
                <Link className="button button-secondary" href={`/garments/${id}/works-with`}>
                  Works with this
                </Link>
              )}
              <Dialog.Root>
                <Dialog.Trigger className="button button-danger">Delete piece</Dialog.Trigger>
                <Dialog.Portal>
                  <Dialog.Backdrop className="dialog-backdrop" />
                  <Dialog.Popup className="dialog-popup">
                    <Dialog.Title>Remove this piece?</Dialog.Title>
                    <Dialog.Description>
                      This permanently deletes {garment.name} and its associated images. You cannot
                      undo this.
                    </Dialog.Description>
                    {remove.error && <p role="alert">{remove.error.message}</p>}
                    <div className="form-actions">
                      <Dialog.Close className="button button-secondary">Keep piece</Dialog.Close>
                      <Button
                        variant="danger"
                        disabled={remove.isPending}
                        onClick={() => remove.mutate()}
                      >
                        Permanently delete
                      </Button>
                    </div>
                  </Dialog.Popup>
                </Dialog.Portal>
              </Dialog.Root>
            </div>
          </div>
        )}
      </div>
      {garment.processingStatus === "READY" && <WearLog target={{ garmentId: id }} />}
      <WearHistory garmentId={id} />
    </div>
  );
}
