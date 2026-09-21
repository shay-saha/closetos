import { OutfitStudio } from "@/features/outfits/outfit-studio";
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ garment?: string | string[] }>;
}) {
  const { garment } = await searchParams;
  return <OutfitStudio garmentId={typeof garment === "string" ? garment : undefined} />;
}
