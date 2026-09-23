"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { GarmentForm } from "./garment-form";
import { GarmentArt } from "./garment-art";
import type { Garment, GarmentMetadata } from "./types";
import { PhotoCapture } from "@/features/capture/photo-capture";

export function AddGarment() {
  const router = useRouter();
  const client = useQueryClient();
  const create = useMutation({
    mutationFn: (metadata: GarmentMetadata) =>
      api<Garment>("garments", { method: "POST", body: JSON.stringify(metadata) }),
    onSuccess: async (garment) => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ["garments"] }),
        client.invalidateQueries({ queryKey: ["search"] }),
      ]);
      router.push(`/garments/${garment.id}`);
    },
  });
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">An intentional addition</p>
          <h1>A place for your piece.</h1>
          <p>Add the details you know. You can always come back to fill in the rest.</p>
        </div>
      </div>
      <PhotoCapture />
      <h2 className="manual-entry-heading">Or start with the details.</h2>
      <div className="form-layout">
        <GarmentArt garment={{ category: "TOP" }} />
        <GarmentForm submit={create.mutate} pending={create.isPending} error={create.error} />
      </div>
    </div>
  );
}
