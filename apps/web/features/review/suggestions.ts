import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { metadataSchema, type GarmentMetadata } from "@/features/garments/types";

export const suggestionFields = {
  category: ["Category", "category"],
  subcategory: ["Subcategory", "subcategory"],
  primaryColour: ["Colour", "primaryColourName"],
  secondaryColours: ["Secondary colours", "secondaryColours"],
  pattern: ["Pattern", "pattern"],
  materialEstimate: ["Material estimate", "material"],
  length: ["Length", "length"],
  formality: ["Formality", "formality"],
  seasonTags: ["Seasons", "seasonTags"],
  styleTags: ["Style tags", "styleTags"],
  occasionTags: ["Occasions", "occasionTags"],
  brand: ["Brand", "brand"],
  notes: ["Notes", "notes"],
} as const;

export type Suggestion = {
  id: string;
  version: number;
  status: "PENDING" | "ACCEPTED" | "REJECTED" | "SUPERSEDED";
  suggestions: Record<
    keyof typeof suggestionFields,
    { value: string | string[] | null; confidence: number }
  >;
};

export function useSuggestions(garmentId: string) {
  return useQuery({
    queryKey: ["suggestions", garmentId],
    queryFn: ({ signal }) => api<Suggestion[]>(`garments/${garmentId}/suggestions`, { signal }),
  });
}

export function suggestedMetadata(
  current: GarmentMetadata,
  suggestion: Suggestion,
): GarmentMetadata {
  const values = Object.fromEntries(
    Object.entries(suggestionFields).map(([field, [, canonical]]) => [
      canonical,
      suggestion.suggestions[field as keyof typeof suggestionFields].value,
    ]),
  );
  return metadataSchema.parse({
    ...current,
    ...Object.fromEntries(Object.entries(values).filter(([, value]) => value !== null)),
  });
}
