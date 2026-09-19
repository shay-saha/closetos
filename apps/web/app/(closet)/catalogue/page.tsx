import { Suspense } from "react";
import { Catalogue } from "@/features/garments/catalogue";
import { LoadingState } from "@/components/ui/feedback";

export default function CataloguePage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <Catalogue />
    </Suspense>
  );
}
