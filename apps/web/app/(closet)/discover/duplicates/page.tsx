import { Suspense } from "react";
import { LoadingState } from "@/components/ui/feedback";
import { PotentialDuplicates } from "@/features/insights/potential-duplicates";

export default function DuplicatesPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <PotentialDuplicates />
    </Suspense>
  );
}
