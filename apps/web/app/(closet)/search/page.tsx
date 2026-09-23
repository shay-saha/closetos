import { Suspense } from "react";
import { WardrobeSearch } from "@/features/search/wardrobe-search";
import { LoadingState } from "@/components/ui/feedback";

export default function SearchPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <WardrobeSearch />
    </Suspense>
  );
}
