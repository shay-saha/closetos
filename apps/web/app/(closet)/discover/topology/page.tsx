import { Suspense } from "react";
import { LoadingState } from "@/components/ui/feedback";
import { WardrobeTopology } from "@/features/topology/wardrobe-topology";

export default function TopologyPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <WardrobeTopology />
    </Suspense>
  );
}
