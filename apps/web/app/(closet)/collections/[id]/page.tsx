import { Suspense } from "react";
import { CollectionEditor } from "@/features/collections/collection-editor";
import { LoadingState } from "@/components/ui/feedback";
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense fallback={<LoadingState />}>
      <CollectionEditor id={id} />
    </Suspense>
  );
}
