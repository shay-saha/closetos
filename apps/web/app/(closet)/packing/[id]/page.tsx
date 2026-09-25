import { PackingEditor } from "@/features/packing/packing-editor";

export default async function PackingDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <PackingEditor id={id} />;
}
