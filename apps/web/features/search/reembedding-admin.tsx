"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/feedback";

type ReembeddingJob = {
  id: string;
  wardrobeId: string | null;
  model: {
    provider: string;
    modelId: string;
    modelVersion: string | null;
    pipelineVersion: string;
    dimensions: number;
  };
  state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "PARTIAL_FAILURE";
  total: number;
  queued: number;
  running: number;
  succeeded: number;
  skipped: number;
  failed: number;
  failureCounts: Record<string, number>;
  requestedAt: string;
  completedAt: string | null;
};
const jobNames = {
  QUEUED: "Queued",
  RUNNING: "Preparing pieces",
  SUCCEEDED: "Complete",
  FAILED: "Failed",
  PARTIAL_FAILURE: "Complete with failures",
};

export function ReembeddingAdmin() {
  const client = useQueryClient();
  const [scope, setScope] = useState("current");
  const [wardrobeId, setWardrobeId] = useState("");
  const submission = useRef<{ body: string; key: string }>(undefined);
  const jobs = useQuery({
    queryKey: ["reembedding-jobs"],
    queryFn: ({ signal }) => api<ReembeddingJob[]>("admin/search/embedding-jobs", { signal }),
    retry: (attempt, error) => attempt < 1 && (!(error instanceof ApiError) || error.status >= 500),
    refetchInterval: (query) => (query.state.data?.some((job) => !job.completedAt) ? 2000 : 60_000),
  });
  const finished = jobs.data
    ?.filter((job) => job.completedAt)
    .map((job) => job.id)
    .join(",");
  useEffect(() => {
    if (finished) void client.invalidateQueries({ queryKey: ["search"] });
  }, [client, finished]);
  const start = useMutation({
    mutationFn: () => {
      const body = JSON.stringify(
        scope === "all"
          ? { allWardrobes: true }
          : scope === "selected"
            ? { wardrobeId: wardrobeId.trim() }
            : {},
      );
      if (submission.current?.body !== body)
        submission.current = { body, key: crypto.randomUUID() };
      return api<ReembeddingJob>("admin/search/embedding-jobs", {
        method: "POST",
        body,
        headers: { "Idempotency-Key": submission.current.key },
      });
    },
    onSuccess: (job) => {
      submission.current = undefined;
      client.setQueryData<ReembeddingJob[]>(["reembedding-jobs"], (current) =>
        [job, ...(current ?? []).filter((item) => item.id !== job.id)].slice(0, 20),
      );
      void client.invalidateQueries({ queryKey: ["reembedding-jobs"] });
    },
  });
  if (jobs.isPending || (jobs.error instanceof ApiError && jobs.error.status === 403)) return null;
  return (
    <section className="reembedding-admin" aria-label="Search administration">
      <p className="eyebrow">Administrator controls</p>
      <h2>Prepare search again.</h2>
      <p>
        Rebuild search preparation for reviewed pieces. This runs in the background; valid existing
        embeddings stay usable while they are regenerated.
      </p>
      {jobs.isError ? (
        <ErrorState error={jobs.error} retry={jobs.refetch} />
      ) : (
        <>
          <form
            className="garment-form"
            onSubmit={(event) => {
              event.preventDefault();
              start.mutate();
            }}
          >
            <label>
              Rebuild scope
              <select
                value={scope}
                disabled={start.isPending}
                onChange={(event) => setScope(event.target.value)}
              >
                <option value="current">This wardrobe</option>
                <option value="selected">A specific wardrobe</option>
                <option value="all">All wardrobes</option>
              </select>
            </label>
            {scope === "selected" && (
              <label>
                Wardrobe identifier
                <input
                  value={wardrobeId}
                  disabled={start.isPending}
                  required
                  pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                  onChange={(event) => setWardrobeId(event.target.value)}
                />
              </label>
            )}
            {scope === "all" && (
              <p className="small">Rebuilds reviewed pieces across every account.</p>
            )}
            {start.error && (
              <p className="field-error" role="alert">
                {start.error.message} Submit again to retry the same request.
              </p>
            )}
            <Button type="submit" disabled={start.isPending}>
              {start.isPending ? "Queueing rebuild…" : "Start search rebuild"}
            </Button>
          </form>
          {start.isSuccess && (
            <p className="processing-notice" role="status">
              Your rebuild is recorded. Its progress is saved even if you leave this page.
            </p>
          )}
          <div className="reembedding-jobs">
            {jobs.data?.map((job) => (
              <article className="reembedding-job" key={job.id} aria-label={`Rebuild ${job.id}`}>
                <h3>{jobNames[job.state]}</h3>
                <p className="small">
                  {job.wardrobeId ? `Wardrobe ${job.wardrobeId}` : "All wardrobes"} ·{" "}
                  {new Date(job.requestedAt).toLocaleString()}
                </p>
                <progress
                  aria-label="Completed rebuild tasks"
                  value={job.succeeded + job.skipped + job.failed}
                  max={job.total || 1}
                />
                <dl className="reembedding-counts" aria-live="polite">
                  {[
                    ["Pieces", job.total],
                    ["Queued", job.queued],
                    ["Running", job.running],
                    ["Prepared", job.succeeded],
                    ["Skipped", job.skipped],
                    ["Failed", job.failed],
                  ].map(([label, count]) => (
                    <div key={label}>
                      <dt>{label}</dt>
                      <dd>{count}</dd>
                    </div>
                  ))}
                </dl>
                {job.skipped > 0 && (
                  <p className="small">
                    Skipped pieces were removed or no longer ready for search preparation.
                  </p>
                )}
                {job.failed > 0 && (
                  <p className="field-error">
                    {job.failureCounts.EMBEDDING_MODEL_CHANGED
                      ? "The configured model changed during this rebuild."
                      : "Some pieces could not be prepared."}{" "}
                    Start another rebuild to retry using the current model.
                  </p>
                )}
                <details>
                  <summary>Model and job details</summary>
                  <dl className="detail-properties">
                    {[
                      ["Job", job.id],
                      ["Provider", job.model.provider],
                      ["Model", job.model.modelId],
                      ["Version", job.model.modelVersion ?? "Not supplied"],
                      ["Pipeline", job.model.pipelineVersion],
                      ["Dimensions", job.model.dimensions],
                    ].map(([label, value]) => (
                      <div key={label}>
                        <dt>{label}</dt>
                        <dd>{value}</dd>
                      </div>
                    ))}
                  </dl>
                </details>
              </article>
            ))}
          </div>
          {jobs.data?.length === 0 && (
            <p className="small">No search rebuilds have been requested yet.</p>
          )}
        </>
      )}
    </section>
  );
}
