"use client";

import { useDeferredValue, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { usePackingLists } from "./queries";
import { planNames } from "./capsule";

export function PackingOverview() {
  const [search, setSearch] = useState("");
  const deferred = useDeferredValue(search);
  const query = usePackingLists(deferred);
  const trips = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="page packing-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Ready for somewhere new</p>
          <h1>A wardrobe for the journey.</h1>
          <p>Small capsules. Complete outfits. Your favourite pieces, ready to go.</p>
        </div>
        <Link href="/packing/new" className="button button-primary">
          Create a trip
        </Link>
      </div>
      <label className="packing-search">
        Find a trip
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
      ) : !trips.length ? (
        <div className="empty-state">
          <h2>{search ? "No matching trips." : "A little room for adventure."}</h2>
          <p>
            {search
              ? "Try another trip name."
              : "Start with your dates, weather, and plans. Your wardrobe will do the rest."}
          </p>
        </div>
      ) : (
        <ul className="packing-trips">
          {trips.map((trip) => (
            <li key={trip.id}>
              <Link href={`/packing/${trip.id}`}>
                <h2>{trip.name}</h2>
                <p>
                  {trip.startDate} – {trip.endDate}
                  {trip.locationText ? ` · ${trip.locationText}` : ""}
                </p>
                <span>{trip.status ? planNames[trip.status] : "Trip draft"}</span>
                {trip.itemCount > 0 && (
                  <p>
                    {trip.itemCount} pieces · {trip.packedCount} packed
                  </p>
                )}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {query.hasNextPage && (
        <div className="load-more">
          <Button
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            More trips
          </Button>
        </div>
      )}
    </div>
  );
}
