import { Suspense } from "react";
import { LoadingState } from "@/components/ui/feedback";
import { WorksWith } from "@/features/insights/works-with";

export default async function WorksWithPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <Suspense fallback={<LoadingState />}>
      <WorksWith id={id} />
    </Suspense>
  );
}
