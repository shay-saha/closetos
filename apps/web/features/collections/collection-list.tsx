"use client";

import Link from "next/link";
import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { api } from "@/lib/api";
import type { CollectionPage } from "./types";

export function CollectionList() {
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const query = useInfiniteQuery({
    queryKey: ["collections", search, type],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => {
      const params = new URLSearchParams({ q: search });
      if (type) params.set("type", type);
      if (pageParam) params.set("cursor", pageParam);
      return api<CollectionPage>(`collections?${params}`, { signal });
    },
    getNextPageParam: (page) => page.nextCursor,
  });
  const collections = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">A wardrobe with a little structure</p>
          <h1>Your collections.</h1>
          <p>
            Keep a capsule together, or save rules that find the right pieces as your wardrobe
            changes.
          </p>
        </div>
        <Link className="button button-primary" href="/collections/new">
          Create a collection
        </Link>
      </div>
      <div className="filters">
        <form
          className="search"
          onSubmit={(event) => {
            event.preventDefault();
            setSearch(String(new FormData(event.currentTarget).get("q") ?? ""));
          }}
        >
          <label>
            Find a collection
            <input name="q" type="search" placeholder="Collection name…" />
          </label>
        </form>
        <label>
          Collection type
          <select value={type} onChange={(event) => setType(event.target.value)}>
            <option value="">All collections</option>
            <option value="MANUAL">Manually selected</option>
            <option value="SMART">Smart rules</option>
          </select>
        </label>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : collections.length === 0 ? (
        <div className="empty-state">
          <h2>{search || type ? "No matching collections." : "Give a few pieces a home."}</h2>
          <p>Create a travel capsule, a work edit, or a view of pieces you rarely wear.</p>
        </div>
      ) : (
        <div className="collection-grid">
          {collections.map((collection) => (
            <Link
              className="collection-card"
              href={`/collections/${collection.id}`}
              key={collection.id}
            >
              <p className="eyebrow">
                {collection.type === "SMART" ? "Smart collection" : "Manually selected"}
              </p>
              <h2>{collection.name}</h2>
              <p>
                {collection.type === "SMART"
                  ? "Updates from your saved rules"
                  : `${collection.garmentIds.length} selected pieces`}
              </p>
            </Link>
          ))}
        </div>
      )}
      {query.hasNextPage && (
        <div className="load-more">
          <Button
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            More collections
          </Button>
        </div>
      )}
    </div>
  );
}
