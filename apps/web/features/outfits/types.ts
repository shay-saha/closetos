export type OutfitItem = {
  garmentId: string;
  x: number;
  y: number;
  scale: number;
  rotation: number;
  zIndex: number;
};
export type OutfitInput = {
  name: string;
  occasion: string | null;
  season: string | null;
  rating: number | null;
  tags: string[];
  notes: string | null;
  archived: boolean;
  items: OutfitItem[];
};
export type Outfit = OutfitInput & {
  id: string;
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type OutfitPage = { items: Outfit[]; nextCursor: string | null };

export function moveItem(item: OutfitItem, x: number, y: number): OutfitItem {
  return { ...item, x: Math.max(0, Math.min(100, x)), y: Math.max(0, Math.min(100, y)) };
}
