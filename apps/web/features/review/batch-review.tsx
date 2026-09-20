"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "@/features/garments/queries";
import { GarmentArt } from "@/features/garments/garment-art";
import { GarmentReview } from "./garment-review";

export function BatchReview() {
  const query = useGarments("processingStatus=READY_FOR_REVIEW&sort=OLDEST", 5000);
  const [finished, setFinished] = useState<string[]>([]);
  const [skipped, setSkipped] = useState<string[]>([]);
  const heading = useRef<HTMLHeadingElement>(null);
  const { hasNextPage, isFetchingNextPage, fetchNextPage, isFetchNextPageError } = query;
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage && !isFetchNextPageError) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage]);
  if (query.isPending)
    return (
      <div className="page">
        <LoadingState />
      </div>
    );
  if (query.isError && !query.data) return <ErrorState error={query.error} retry={query.refetch} />;
  const waiting = query.data.pages
    .flatMap((page) => page.items)
    .filter((item) => !finished.includes(item.id));
  const current = waiting.find((item) => !skipped.includes(item.id));
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">One piece at a time</p>
          <h1 ref={heading} tabIndex={-1}>
            Make them yours.
          </h1>
          <p role="status">
            {finished.length} reviewed · {waiting.length}
            {hasNextPage ? "+" : ""} ready for review
          </p>
        </div>
        <Link href="/add" className="button button-secondary">
          Capture more pieces
        </Link>
      </div>
      {isFetchNextPageError && (
        <ErrorState
          error={query.error ?? new Error("More pieces could not be loaded.")}
          retry={() => void fetchNextPage()}
        />
      )}
      {current ? (
        <div className="detail-grid" key={current.id}>
          <div>
            <GarmentArt garment={current} size="display" />
            <Button
              variant="quiet"
              onClick={() => {
                setSkipped([...skipped, current.id]);
                heading.current?.focus();
              }}
            >
              Review this piece later
            </Button>
          </div>
          <GarmentReview
            garment={current}
            completed={() => {
              setFinished([...finished, current.id]);
              heading.current?.focus();
            }}
          />
        </div>
      ) : (
        <div className="empty-state">
          <h2>{waiting.length ? "The remaining pieces can wait." : "You’re all caught up."}</h2>
          <p>
            {waiting.length
              ? "Come back to the pieces you skipped whenever you’re ready."
              : "New photographs appear here when processing finishes."}
          </p>
          {waiting.length > 0 && (
            <Button onClick={() => setSkipped([])}>Review skipped pieces</Button>
          )}
          <Link className="button button-secondary" href="/catalogue">
            Your collection →
          </Link>
        </div>
      )}
    </div>
  );
}
