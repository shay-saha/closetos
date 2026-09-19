import { Suspense } from "react";
import { Wardrobe } from "@/features/wardrobe/wardrobe";
import { LoadingState } from "@/components/ui/feedback";

export default function WardrobePage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <Wardrobe />
    </Suspense>
  );
}
