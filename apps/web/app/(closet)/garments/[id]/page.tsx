import { GarmentDetail } from "@/features/garments/garment-detail";
export default async function GarmentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <GarmentDetail id={id} />;
}
