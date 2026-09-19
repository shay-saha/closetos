"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { GarmentForm } from "./garment-form";
import { GarmentArt } from "./garment-art";
import type { Garment, GarmentMetadata } from "./types";

export function AddGarment() {
  const router = useRouter();
  const client = useQueryClient();
  const create = useMutation({
    mutationFn: (metadata: GarmentMetadata) =>
      api<Garment>("garments", { method: "POST", body: JSON.stringify(metadata) }),
    onSuccess: async (garment) => {
      await client.invalidateQueries({ queryKey: ["garments"] });
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
      <div className="form-layout">
        <GarmentArt garment={{ category: "TOP" }} />
        <GarmentForm submit={create.mutate} pending={create.isPending} error={create.error} />
      </div>
    </div>
  );
}
