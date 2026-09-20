"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Camera, Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCapture } from "./capture-provider";
import type { QueuedUpload } from "./upload-store";

function Preview({ upload }: { upload: QueuedUpload }) {
  const image = useRef<HTMLImageElement>(null);
  useEffect(() => {
    if (!upload.file) return;
    const url = URL.createObjectURL(upload.file);
    if (image.current) image.current.src = url;
    return () => URL.revokeObjectURL(url);
  }, [upload.file]);
  // Local blob previews cannot be served by the Next image optimiser.
  return upload.file ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img ref={image} alt="" className="upload-preview" />
  ) : (
    <span className="upload-preview" aria-hidden="true">
      ✓
    </span>
  );
}

export function PhotoCapture() {
  const { engine, items } = useCapture();
  const library = useRef<HTMLInputElement>(null);
  const camera = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string>();
  async function choose(files: File[]) {
    setError(undefined);
    try {
      if (!engine) throw new Error("Your upload queue is still loading. Try again shortly.");
      await engine.add(files);
    } catch (error) {
      setError(error instanceof Error ? error.message : "Your photographs could not be added.");
    }
  }
  async function retry(id: string) {
    try {
      await engine?.retry(id);
    } catch (error) {
      setError(error instanceof Error ? error.message : "The upload could not be retried.");
    }
  }
  return (
    <section
      className="photo-capture"
      aria-labelledby="capture-heading"
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => {
        event.preventDefault();
        void choose([...event.dataTransfer.files]);
      }}
    >
      <p className="eyebrow">One photo is enough</p>
      <h2 id="capture-heading">Let your piece take shape.</h2>
      <p>
        Lay it flat or hang it against a clear background. We’ll remove the background; you can
        review the details when it’s ready.
      </p>
      <div className="form-actions">
        <Button disabled={!engine?.isReady()} onClick={() => library.current?.click()}>
          <Upload size={16} /> Choose photographs
        </Button>
        <Button
          variant="secondary"
          disabled={!engine?.isReady()}
          onClick={() => camera.current?.click()}
        >
          <Camera size={16} /> Take a photo
        </Button>
      </div>
      <input
        ref={library}
        type="file"
        accept="image/jpeg,image/png,image/webp,image/heic,image/heif,.heic,.heif"
        multiple
        aria-label="Photograph files"
        className="sr-only"
        onChange={(event) => {
          void choose([...(event.target.files ?? [])]);
          event.target.value = "";
        }}
      />
      <input
        ref={camera}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label="Camera photograph"
        className="sr-only"
        onChange={(event) => {
          void choose([...(event.target.files ?? [])]);
          event.target.value = "";
        }}
      />
      <p className="small">
        JPEG, PNG, WebP or HEIC · up to 25 MB each. Drop photos here or choose several at once. Your
        queue resumes after a refresh.
      </p>
      {error && <p role="alert">{error}</p>}
      {items.some((item) => item.state === "ready") && (
        <Link href="/review" className="button button-secondary">
          Review completed photographs →
        </Link>
      )}
      {!!items.length && (
        <ul className="upload-list" aria-label="Upload queue">
          {items.map((upload) => (
            <li key={upload.id}>
              <Preview upload={upload} />
              <div className="upload-copy">
                <strong>{upload.filename}</strong>
                <p role="status">
                  {upload.state === "uploading"
                    ? `Uploading · ${upload.progress}%`
                    : upload.state === "processing"
                      ? "Removing the background…"
                      : upload.state === "ready"
                        ? "Ready to review"
                        : upload.state === "reviewed"
                          ? "Ready in your wardrobe"
                          : upload.state === "queued"
                            ? "Waiting to upload"
                            : upload.error}
                </p>
                {upload.state === "uploading" && (
                  <progress
                    aria-label={`Uploading ${upload.filename}`}
                    value={upload.progress}
                    max={100}
                  />
                )}
                {upload.garmentId && (
                  <Link href={`/garments/${upload.garmentId}`}>
                    {upload.state === "ready" ? "Review your piece →" : "Open piece →"}
                  </Link>
                )}
              </div>
              <div className="upload-actions">
                {upload.state === "failed" && upload.canRetry !== false && (
                  <Button variant="quiet" onClick={() => void retry(upload.id)}>
                    Retry
                  </Button>
                )}
                {["ready", "reviewed", "failed", "queued"].includes(upload.state) && (
                  <Button variant="quiet" onClick={() => void engine?.dismiss(upload.id)}>
                    Dismiss
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
