import { z } from "zod";

export const categories = [
  "TOP",
  "DRESS",
  "BOTTOM",
  "OUTERWEAR",
  "SHOES",
  "BAG",
  "JEWELLERY",
  "ACCESSORY",
  "OTHER",
] as const;
export type Category = (typeof categories)[number];
export const categoryNames: Record<Category, string> = {
  TOP: "Tops",
  DRESS: "Dresses",
  BOTTOM: "Bottoms",
  OUTERWEAR: "Outerwear",
  SHOES: "Shoes",
  BAG: "Bags",
  JEWELLERY: "Jewellery",
  ACCESSORY: "Accessories",
  OTHER: "Other",
};
export const metadataSchema = z
  .object({
    name: z.string().trim().min(1, "Give this piece a name.").max(160),
    category: z.enum(categories),
    subcategory: z.string().max(80).nullable().optional(),
    brand: z.string().max(120).nullable().optional(),
    sizeLabel: z.string().max(40).nullable().optional(),
    primaryColourName: z.string().max(60).nullable().optional(),
    primaryColourHex: z
      .string()
      .regex(/^#[\da-fA-F]{6}$/)
      .nullable()
      .optional(),
    material: z.string().max(120).nullable().optional(),
    pattern: z.string().max(80).nullable().optional(),
    length: z.string().max(60).nullable().optional(),
    formality: z.string().max(60).nullable().optional(),
    secondaryColours: z.array(z.string()).optional(),
    seasonTags: z.array(z.string()).optional(),
    occasionTags: z.array(z.string()).optional(),
    styleTags: z.array(z.string()).optional(),
    purchasePrice: z.number().nonnegative().nullable().optional(),
    purchaseCurrency: z
      .string()
      .regex(/^[A-Z]{3}$/)
      .nullable()
      .optional(),
    purchaseDate: z.string().nullable().optional(),
    notes: z.string().max(4000).nullable().optional(),
  })
  .refine((value) => value.purchasePrice == null || value.purchaseCurrency != null, {
    message: "Choose a currency for the purchase price.",
    path: ["purchaseCurrency"],
  });
export type GarmentMetadata = z.infer<typeof metadataSchema>;
export type Garment = GarmentMetadata & {
  id: string;
  status: "AVAILABLE" | "LAUNDRY" | "PACKED" | "LENT" | "ARCHIVED";
  processingStatus: string;
  wearCount: number;
  lastWornAt: string | null;
  costPerWear: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type GarmentPage = { items: Garment[]; nextCursor: string | null };
