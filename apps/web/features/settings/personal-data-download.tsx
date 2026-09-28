"use client";

import { useEffect, useRef } from "react";
import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { personalDataFile } from "@/lib/personal-data-download";

export function PersonalDataDownload() {
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const download = useMutation({
    mutationFn: async () => {
      controller.current = new AbortController();
      const file = await personalDataFile(controller.current.signal);
      controller.current.signal.throwIfAborted();
      const url = URL.createObjectURL(file);
      const link = document.createElement("a");
      link.href = url;
      link.download = `closetos-data-${new Date().toISOString().slice(0, 10)}.json`;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
  return (
    <section className="privacy-preference" aria-labelledby="data-download-title">
      <h2 id="data-download-title">Your wardrobe data</h2>
      <p id="data-download-explanation" className="small">
        Download a JSON file with your profile, pieces, suggestions, outfits, wear history,
        collections, packing lists and search data. It includes private links to photographs that
        are still stored. Photos are downloaded separately; their links expire after fifteen
        minutes.
      </p>
      <Button
        type="button"
        disabled={download.isPending}
        aria-describedby="data-download-explanation"
        onClick={() => download.mutate()}
      >
        {download.isPending ? "Preparing your data…" : "Download my data"}
      </Button>
      {download.isSuccess && (
        <p role="status" className="small">
          Your data file is ready.
        </p>
      )}
      {download.isError && (
        <p role="alert" className="field-error">
          {download.error.message}
        </p>
      )}
    </section>
  );
}
