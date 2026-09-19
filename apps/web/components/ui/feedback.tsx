"use client";

import Link from "next/link";
import { ApiError } from "@/lib/api";
import { Button } from "./button";

export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  return (
    <div className="feedback" role="alert">
      <h2>Something needs another try.</h2>
      <p>{error.message}</p>
      {error instanceof ApiError && error.status === 401 ? (
        <Link className="button button-primary" href="/signin">
          Sign in again
        </Link>
      ) : (
        retry && (
          <Button variant="secondary" onClick={retry}>
            Try again
          </Button>
        )
      )}
    </div>
  );
}

export function LoadingState() {
  return (
    <div className="loading-grid" role="status" aria-label="Loading your wardrobe">
      {Array.from({ length: 6 }, (_, index) => (
        <div className="skeleton" key={index} />
      ))}
      <span className="sr-only">Loading your wardrobe…</span>
    </div>
  );
}
