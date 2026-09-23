"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { advancedFilters } from "@/features/garments/filters";
import { categories, categoryNames } from "@/features/garments/types";
import { readSearchPhoto, useSearchPhotos } from "./search-photos";
import { searchParameters } from "./types";
import { useSearchSource } from "./queries";

export function SearchForm({ filters }: { filters: string }) {
  const params = new URLSearchParams(filters);
  const router = useRouter();
  const { photos, add } = useSearchPhotos();
  const [photoId, setPhotoId] = useState(params.get("photo"));
  const [sourceId, setSourceId] = useState(params.get("similarToGarmentId"));
  const [mode, setMode] = useState(
    params.get("mode") === "KEYWORD"
      ? "KEYWORD"
      : params.get("mode") === "SEMANTIC"
        ? "SEMANTIC"
        : "HYBRID",
  );
  const [reading, setReading] = useState(false);
  const [photoError, setPhotoError] = useState<string>();
  const selection = useRef(0);
  const photo = photos.find((item) => item.id === photoId);
  const source = useSearchSource(sourceId);
  useEffect(
    () => () => {
      selection.current++;
    },
    [],
  );

  async function choosePhoto(file?: File) {
    if (!file) return;
    const attempt = ++selection.current;
    setReading(true);
    setPhotoError(undefined);
    try {
      const dataUrl = await readSearchPhoto(file);
      if (selection.current !== attempt) return;
      setPhotoId(add(file.name, dataUrl));
      setSourceId(null);
      if (mode === "KEYWORD") setMode("SEMANTIC");
    } catch (error) {
      if (selection.current === attempt) setPhotoError((error as Error).message);
    } finally {
      if (selection.current === attempt) setReading(false);
    }
  }

  return (
    <form
      className="wardrobe-search-form"
      onSubmit={(event) => {
        event.preventDefault();
        const next = new URLSearchParams();
        for (const [key, value] of new FormData(event.currentTarget))
          if (typeof value === "string" && value.trim()) next.set(key, value.trim());
        if (!next.get("q") && !next.get("photo") && !next.get("similarToGarmentId"))
          next.delete("mode");
        router.push(`/search?${searchParameters(next)}`, { scroll: false });
      }}
    >
      <div className="search-query-row">
        <label className="field">
          Describe what you have in mind
          <input
            type="search"
            name="q"
            maxLength={500}
            defaultValue={params.get("q") ?? ""}
            placeholder="Black formal dress not worn recently…"
            aria-describedby="search-mode-help"
          />
        </label>
        <label className="field">
          Search by
          <select name="mode" value={mode} onChange={(event) => setMode(event.target.value)}>
            <option value="HYBRID">Words & description</option>
            <option value="SEMANTIC">Description & appearance</option>
            <option value="KEYWORD" disabled={!!photoId || !!sourceId}>
              Words in details
            </option>
          </select>
        </label>
        <Button type="submit" disabled={reading || !!photoError}>
          Search wardrobe
        </Button>
      </div>
      <p className="small" id="search-mode-help">
        {mode === "HYBRID"
          ? "Combines related pieces with matching words. Recognised colours, categories, and wear dates become filters you can review below."
          : mode === "SEMANTIC"
            ? "Finds related descriptions and appearances. Only the filters you choose below restrict the results."
            : "Matches words in saved garment details. Works while photographs are still being prepared."}
      </p>
      {sourceId && (
        <div className="search-source">
          <p>
            Find pieces like{" "}
            {source.data ? (
              <Link href={`/garments/${sourceId}`}>{source.data.name}</Link>
            ) : source.isError ? (
              "a piece that is no longer available"
            ) : (
              "your selected piece"
            )}
            .
          </p>
          <Button type="button" variant="quiet" onClick={() => setSourceId(null)}>
            Remove reference piece
          </Button>
          <input name="similarToGarmentId" type="hidden" value={sourceId} />
        </div>
      )}
      <details className="search-photo-options" open={!!photoId || undefined}>
        <summary>Search with a photograph</summary>
        <div className="search-photo-picker">
          {photo && (
            <Image
              src={photo.dataUrl}
              alt="Your search reference photograph"
              width={120}
              height={120}
              unoptimized
            />
          )}
          <div>
            <label className="field">
              Reference photograph
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                aria-describedby="search-photo-help"
                aria-invalid={!!photoError}
                onChange={(event) => {
                  void choosePhoto(event.target.files?.[0]);
                  event.target.value = "";
                }}
              />
            </label>
            <p className="small" id="search-photo-help">
              JPEG, PNG, or WebP, up to 8 MB. Used for this search; it is not added to your
              wardrobe. Reselect it after a reload or when sharing the link.
            </p>
            {reading && <p role="status">Reading your photograph…</p>}
            {photo && <p className="small search-photo-name">Selected: {photo.name}</p>}
            {photoError && (
              <p role="alert" className="field-error">
                {photoError}
              </p>
            )}
            {(photoId || photoError) && (
              <Button
                type="button"
                variant="quiet"
                onClick={() => {
                  selection.current++;
                  setReading(false);
                  setPhotoId(null);
                  setPhotoError(undefined);
                }}
              >
                Remove photograph
              </Button>
            )}
          </div>
        </div>
        {photoId && <input name="photo" type="hidden" value={photoId} />}
      </details>
      <details className="advanced-filters">
        <summary>Choose filters</summary>
        <p className="small">These filters always apply, whatever search mode you choose.</p>
        <div className="advanced-filter-grid">
          <label>
            Category
            <select name="category" defaultValue={params.get("category") ?? ""}>
              <option value="">All pieces</option>
              {categories.map((category) => (
                <option key={category} value={category}>
                  {categoryNames[category]}
                </option>
              ))}
            </select>
          </label>
          <label>
            Availability
            <select name="status" defaultValue={params.get("status") ?? ""}>
              <option value="">All active</option>
              {["AVAILABLE", "LAUNDRY", "PACKED", "LENT", "ARCHIVED"].map((status) => (
                <option key={status} value={status}>
                  {status.toLowerCase()}
                </option>
              ))}
            </select>
          </label>
          {advancedFilters.map(([key, label, type]) => (
            <label key={key}>
              {label}
              <input
                name={key}
                type={type}
                defaultValue={params.get(key) ?? ""}
                min={type === "number" ? 0 : undefined}
                step={type === "number" ? 1 : undefined}
                maxLength={
                  key === "brand" ? 120 : key === "subcategory" ? 80 : key === "size" ? 40 : 60
                }
              />
            </label>
          ))}
        </div>
        <Button type="submit" variant="secondary" disabled={reading || !!photoError}>
          Apply filters
        </Button>
      </details>
      {["view", "sort", "processingStatus"].map(
        (key) =>
          params.get(key) && <input key={key} type="hidden" name={key} value={params.get(key)!} />,
      )}
    </form>
  );
}
