import { OutfitStudio } from "@/features/outfits/outfit-studio";
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <OutfitStudio id={id} />;
}
