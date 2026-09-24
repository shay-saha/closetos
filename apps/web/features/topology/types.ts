import type { Category, Garment } from "@/features/garments/types";

export type GraphNode = {
  id: string;
  name: string;
  category: Category;
  assets: Garment["assets"];
  colour: string | null;
  wearCount: number;
  lastWornAt: string | null;
  costPerWear: number | null;
  purchaseCurrency: string | null;
  indexed: boolean;
};
export type GraphEdge = { source: string; target: string; weight: number };
export type WardrobeGraph = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  eligibleCount: number;
  indexedCount: number;
  truncated: boolean;
  model: { provider: string; modelId: string; dimensions: number } | null;
  embeddingAvailability: "AVAILABLE" | "UNAVAILABLE";
};
export type LayoutInput = { nodes: { id: string }[]; edges: GraphEdge[] };
export type GraphPosition = { id: string; x: number; y: number; z: number };
export type ColourMetric = "category" | "wear";

export const categoryColours: Record<Category, string> = {
  TOP: "#45664c",
  DRESS: "#953f2b",
  BOTTOM: "#355f83",
  OUTERWEAR: "#655288",
  SHOES: "#866624",
  BAG: "#89557a",
  JEWELLERY: "#986249",
  ACCESSORY: "#28716d",
  OTHER: "#626459",
};
export const wearColours = ["#626459", "#355f83", "#45664c", "#953f2b"];
export const wearLabels = ["Never worn", "1–4 wears", "5–19 wears", "20+ wears"];
export function nodeColour(node: GraphNode, metric: ColourMetric) {
  if (metric === "category") return categoryColours[node.category];
  return wearColours[
    node.wearCount === 0 ? 0 : node.wearCount < 5 ? 1 : node.wearCount < 20 ? 2 : 3
  ];
}
