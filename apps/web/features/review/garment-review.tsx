"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { GarmentForm } from "@/features/garments/garment-form";
import { metadataSchema, type Garment, type GarmentMetadata } from "@/features/garments/types";
import { useCapture } from "@/features/capture/capture-provider";
import { api } from "@/lib/api";
import { suggestedMetadata, suggestionFields, useSuggestions } from "./suggestions";

export function GarmentReview({
  garment,
  completed,
  cancel,
}: {
  garment: Garment;
  completed: () => void;
  cancel?: () => void;
}) {
  const query = useSuggestions(garment.id);
  const client = useQueryClient();
  const { engine } = useCapture();
  const suggestion = query.data?.find((item) => item.status === "PENDING");
  const save = useMutation({
    mutationFn: (metadata: GarmentMetadata) =>
      suggestion
        ? api<Garment>(`garments/${garment.id}/suggestions/accept`, {
            method: "POST",
            body: JSON.stringify({
              suggestionId: suggestion.id,
              suggestionVersion: suggestion.version,
              garmentVersion: garment.version,
              corrections: metadata,
            }),
          })
        : api<Garment>(`garments/${garment.id}`, {
            method: "PATCH",
            body: JSON.stringify({ ...metadata, version: garment.version }),
          }),
    onSuccess: async (saved) => {
      client.setQueryData(["garment", garment.id], saved);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["garments"] }),
        client.invalidateQueries({ queryKey: ["search"] }),
        client.invalidateQueries({ queryKey: ["suggestions", garment.id] }),
        engine?.reviewed(garment.id),
      ]);
      completed();
    },
  });
  const reject = useMutation({
    mutationFn: () =>
      api<void>(`garments/${garment.id}/suggestions/reject`, {
        method: "POST",
        body: JSON.stringify({
          suggestionId: suggestion?.id,
          suggestionVersion: suggestion?.version,
        }),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["suggestions", garment.id] }),
  });
  if (query.isPending) return <LoadingState />;
  if (query.isError) return <ErrorState error={query.error} retry={query.refetch} />;
  return (
    <section aria-label="Review garment details">
      <p className="eyebrow">Your eye has the final say</p>
      <h2>Review your piece</h2>
      {suggestion ? (
        <>
          <p>
            These are suggestions from your photograph. Check each detail, especially estimates with
            low confidence. Your changes are saved when you choose “Looks right”.
          </p>
          <dl className="suggestion-list">
            {Object.entries(suggestionFields).map(([field, [label]]) => {
              const { value, confidence } =
                suggestion.suggestions[field as keyof typeof suggestionFields];
              return (
                <div key={field}>
                  <dt>{label}</dt>
                  <dd>
                    {Array.isArray(value)
                      ? value.join(", ") || "Not identified"
                      : (value ?? "Not identified")}
                    <span className={confidence < 0.8 ? "confidence-low" : "small"}>
                      {Math.round(confidence * 100)}% confidence
                      {confidence < 0.8 ? " · please check" : ""}
                    </span>
                  </dd>
                </div>
              );
            })}
          </dl>
          <Button
            variant="secondary"
            disabled={reject.isPending || save.isPending}
            onClick={() => reject.mutate()}
          >
            Reject suggestions
          </Button>
          {reject.error && <p role="alert">{reject.error.message}</p>}
        </>
      ) : (
        <p>Your photograph is ready. Add the details you know and save them to your wardrobe.</p>
      )}
      <GarmentForm
        key={suggestion?.id ?? "manual"}
        initial={
          suggestion ? suggestedMetadata(garment, suggestion) : metadataSchema.parse(garment)
        }
        submit={save.mutate}
        pending={save.isPending || reject.isPending}
        error={save.error}
        submitLabel={suggestion ? "Looks right" : "Save reviewed piece"}
        cancel={cancel}
      />
    </section>
  );
}
