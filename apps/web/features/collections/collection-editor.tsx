"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { GarmentArt } from "@/features/garments/garment-art";
import { Catalogue } from "@/features/garments/catalogue";
import { useGarments } from "@/features/garments/queries";
import { categories, categoryNames } from "@/features/garments/types";
import { api, ApiError } from "@/lib/api";
import { RuleBuilder } from "./rule-builder";
import { emptyRule, type Collection, type SmartQuery } from "./types";

function MemberPicker({
  selected,
  onChange,
}: {
  selected: Set<string>;
  onChange: (selected: Set<string>) => void;
}) {
  const [category, setCategory] = useState("");
  const [status, setStatus] = useState("");
  const query = useGarments(
    new URLSearchParams({
      ...(category ? { category } : {}),
      ...(status ? { status } : {}),
    }).toString(),
  );
  const garments = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <section aria-label="Choose collection pieces">
      <div className="filters">
        <label>
          Selection category
          <select value={category} onChange={(event) => setCategory(event.target.value)}>
            <option value="">All categories</option>
            {categories.map((item) => (
              <option key={item} value={item}>
                {categoryNames[item]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Selection availability
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">All active pieces</option>
            {["AVAILABLE", "LAUNDRY", "PACKED", "LENT", "ARCHIVED"].map((item) => (
              <option key={item} value={item}>
                {item.toLowerCase()}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="form-actions">
        <p role="status">
          {selected.size} selected. Selections stay selected when you change filters.
        </p>
        <Button
          type="button"
          variant="quiet"
          disabled={selected.size === 0}
          onClick={() => onChange(new Set())}
        >
          Clear selection
        </Button>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : garments.length === 0 ? (
        <p className="small">No pieces match this selection filter.</p>
      ) : (
        <div className="collection-picker">
          {garments.map((garment) => (
            <label className="collection-choice" key={garment.id}>
              <GarmentArt garment={garment} />
              <span>
                <input
                  type="checkbox"
                  checked={selected.has(garment.id)}
                  disabled={!selected.has(garment.id) && selected.size >= 5000}
                  onChange={(event) => {
                    const next = new Set(selected);
                    if (event.target.checked) next.add(garment.id);
                    else next.delete(garment.id);
                    onChange(next);
                  }}
                />
                {garment.name}
              </span>
            </label>
          ))}
        </div>
      )}
      {query.hasNextPage && (
        <div className="load-more">
          <Button
            type="button"
            variant="secondary"
            disabled={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            Show more selection pieces
          </Button>
        </div>
      )}
    </section>
  );
}

function Editor({ collection }: { collection?: Collection }) {
  const router = useRouter();
  const client = useQueryClient();
  const [name, setName] = useState(collection?.name ?? "");
  const [type, setType] = useState<Collection["type"]>(collection?.type ?? "MANUAL");
  const [selected, setSelected] = useState(new Set(collection?.garmentIds ?? []));
  const [rules, setRules] = useState<SmartQuery>(
    collection?.queryDefinition ?? { all: [emptyRule()] },
  );
  const [version, setVersion] = useState(collection?.version);
  const save = useMutation({
    mutationFn: () =>
      api<Collection>(collection ? `collections/${collection.id}` : "collections", {
        method: collection ? "PATCH" : "POST",
        body: JSON.stringify({
          name,
          type,
          queryDefinition: type === "SMART" ? rules : null,
          garmentIds: type === "MANUAL" ? [...selected].sort() : [],
          ...(collection ? { version } : {}),
        }),
      }),
    onSuccess: async (saved) => {
      setVersion(saved.version);
      client.setQueryData(["collection", saved.id], saved);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["collections"] }),
        client.invalidateQueries({ queryKey: ["garments"] }),
      ]);
      if (!collection) router.replace(`/collections/${saved.id}`);
    },
  });
  const remove = useMutation({
    mutationFn: () =>
      api<void>(`collections/${collection!.id}?version=${version}`, { method: "DELETE" }),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["collections"] });
      router.replace("/collections");
    },
  });
  return (
    <>
      <div className="page collection-editor">
        <div className="page-intro">
          <div>
            <p className="eyebrow">Keep the pieces that belong together</p>
            <h1>{collection ? "Edit your collection." : "Create a collection."}</h1>
            <p>Choose pieces yourself, or let saved rules keep this collection up to date.</p>
          </div>
          <Link href="/collections" className="button button-secondary">
            All collections
          </Link>
        </div>
        <form
          className="garment-form"
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <fieldset disabled={save.isPending || remove.isPending} className="collection-fields">
            <div className="filters">
              <label>
                Collection name
                <input
                  required
                  maxLength={160}
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </label>
              <label>
                How to collect
                <select
                  value={type}
                  onChange={(event) => setType(event.target.value as Collection["type"])}
                >
                  <option value="MANUAL">Choose pieces manually</option>
                  <option value="SMART">Use smart rules</option>
                </select>
              </label>
            </div>
            {type === "MANUAL" ? (
              <MemberPicker selected={selected} onChange={setSelected} />
            ) : (
              <>
                <p className="small">
                  Rules read the current garment details and wear history. Archived pieces are
                  excluded unless you add an availability rule. Forgotten pieces are those added
                  over 180 days ago and worn fewer than three times.
                </p>
                <RuleBuilder
                  node={"field" in rules || "not" in rules ? { all: [rules] } : rules}
                  onChange={setRules}
                />
              </>
            )}
            <div className="form-actions">
              <Button type="submit">{save.isPending ? "Saving…" : "Save collection"}</Button>
              {collection && (
                <Dialog.Root>
                  <Dialog.Trigger render={<Button variant="danger" type="button" />}>
                    Delete collection
                  </Dialog.Trigger>
                  <Dialog.Portal>
                    <Dialog.Backdrop className="dialog-backdrop" />
                    <Dialog.Popup className="dialog-popup">
                      <Dialog.Title>Delete this collection?</Dialog.Title>
                      <Dialog.Description>
                        The saved collection will be removed. Your garments stay in your wardrobe.
                      </Dialog.Description>
                      <div className="dialog-actions">
                        <Dialog.Close render={<Button variant="secondary" />}>
                          Keep collection
                        </Dialog.Close>
                        <Button
                          variant="danger"
                          disabled={remove.isPending}
                          onClick={() => remove.mutate()}
                        >
                          Delete collection permanently
                        </Button>
                      </div>
                      {remove.isError && <ErrorState error={remove.error} />}
                    </Dialog.Popup>
                  </Dialog.Portal>
                </Dialog.Root>
              )}
            </div>
          </fieldset>
          {save.isSuccess && <p role="status">Collection saved.</p>}
          {save.isError &&
            (save.error instanceof ApiError && save.error.status === 409 ? (
              <div role="alert">
                <p>This collection changed. Reload the current version before saving again.</p>
                <Button type="button" variant="secondary" onClick={() => window.location.reload()}>
                  Reload current collection
                </Button>
              </div>
            ) : (
              <ErrorState error={save.error} />
            ))}
        </form>
      </div>
      {collection && <Catalogue collectionId={collection.id} collectionName={collection.name} />}
    </>
  );
}

export function CollectionEditor({ id }: { id?: string }) {
  const query = useQuery({
    queryKey: ["collection", id],
    queryFn: ({ signal }) => api<Collection>(`collections/${id}`, { signal }),
    enabled: Boolean(id),
  });
  if (id && query.isPending) return <LoadingState />;
  if (id && query.isError) return <ErrorState error={query.error} retry={query.refetch} />;
  return <Editor key={id ?? "new"} collection={id ? query.data : undefined} />;
}
