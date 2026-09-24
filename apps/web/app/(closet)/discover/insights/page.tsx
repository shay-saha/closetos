import { Suspense } from "react";
import { LoadingState } from "@/components/ui/feedback";
import { WardrobeInsights } from "@/features/insights/wardrobe-insights";

export default function InsightsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <WardrobeInsights />
    </Suspense>
  );
}
