import { z } from "zod";
import type { Category, Garment } from "@/features/garments/types";

export const weatherNames = {
  COLD: "Cold",
  MILD: "Mild",
  HOT: "Hot",
  RAIN: "Rain",
} as const;
export const seasonNames = {
  SPRING: "Spring",
  SUMMER: "Summer",
  AUTUMN: "Autumn",
  WINTER: "Winter",
} as const;
export type Weather = keyof typeof weatherNames;
export type Season = keyof typeof seasonNames;

const date = z.string().refine((value) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}, "Choose a valid date.");
const label = z.string().trim().min(1, "Give this occasion a name.").max(80);
const tag = z.string().trim().max(60).nullable();
const pieces = (maximum: number) =>
  z
    .array(z.uuid())
    .max(maximum)
    .refine((ids) => new Set(ids).size === ids.length);

export const tripSchema = z
  .object({
    name: z.string().trim().min(1, "Give your trip a name.").max(160),
    startDate: date,
    endDate: date,
    locationText: z.string().trim().max(200).nullable(),
    constraints: z.object({
      maximumGarments: z.number().int().min(1).max(100),
      weather: z.object({
        minimumTemperatureC: z.number().int().min(-50).max(60).nullable(),
        maximumTemperatureC: z.number().int().min(-50).max(60).nullable(),
        assumptions: z.array(z.enum(["COLD", "MILD", "HOT", "RAIN"])).max(4),
        season: z.enum(["SPRING", "SUMMER", "AUTUMN", "WINTER"]).nullable(),
      }),
      occasions: z
        .array(
          z.object({
            name: label,
            occasionTag: tag,
            formality: tag,
            days: z.number().int().min(1).max(31),
          }),
        )
        .min(1)
        .max(8),
      formalEvents: z
        .array(
          z.object({
            name: label,
            date,
            occasionTag: tag,
            formality: z.string().trim().min(1, "Specify the event's formality.").max(60),
          }),
        )
        .max(16),
      laundryEveryDays: z.number().int().min(0).max(31),
      maximumWearsBetweenLaundry: z.number().int().min(1).max(31),
      requiredGarments: pieces(100),
      excludedGarments: pieces(2000),
    }),
  })
  .superRefine((trip, ctx) => {
    const days = tripDays(trip.startDate, trip.endDate);
    if (days < 1 || days > 31)
      ctx.addIssue({
        code: "custom",
        path: ["endDate"],
        message: "Choose a trip of 1–31 days, including both dates.",
      });
    else if (trip.constraints.occasions.reduce((sum, occasion) => sum + occasion.days, 0) !== days)
      ctx.addIssue({
        code: "custom",
        path: ["constraints", "occasions"],
        message: `Occasions must cover all ${days} trip days, in the order entered.`,
      });
    const weather = trip.constraints.weather;
    if ((weather.minimumTemperatureC == null) !== (weather.maximumTemperatureC == null))
      ctx.addIssue({
        code: "custom",
        path: ["constraints", "weather"],
        message: "Provide both temperature bounds, or leave both empty.",
      });
    else if (weather.minimumTemperatureC == null && weather.assumptions.length === 0)
      ctx.addIssue({
        code: "custom",
        path: ["constraints", "weather"],
        message: "Choose weather assumptions or provide a temperature range.",
      });
    else if (
      weather.minimumTemperatureC != null &&
      weather.maximumTemperatureC! < weather.minimumTemperatureC
    )
      ctx.addIssue({
        code: "custom",
        path: ["constraints", "weather"],
        message: "The maximum temperature cannot be below the minimum.",
      });
    trip.constraints.formalEvents.forEach((event, index) => {
      if (event.date < trip.startDate || event.date > trip.endDate)
        ctx.addIssue({
          code: "custom",
          path: ["constraints", "formalEvents", index, "date"],
          message: "Schedule each event within the trip dates.",
        });
    });
    if (
      trip.constraints.requiredGarments.some((id) => trip.constraints.excludedGarments.includes(id))
    )
      ctx.addIssue({
        code: "custom",
        path: ["constraints", "requiredGarments"],
        message: "A piece cannot be both required and excluded.",
      });
  });

export type Trip = z.infer<typeof tripSchema>;
export type PackingPlan = {
  status: "OPTIMAL" | "FEASIBLE" | "INFEASIBLE" | "TIME_LIMIT";
  selectedGarments: string[];
  outfits: { demandIndex: number; garmentIds: string[] }[];
  warnings: { code: string; detail: string; blocking: boolean }[];
};
export type ConstraintPiece = {
  id: string;
  name: string | null;
  category: Category | null;
  status: Garment["status"] | null;
  processingStatus: string | null;
  present: boolean;
};
export type PackingList = Trip & {
  id: string;
  version: number;
  manualOverride: boolean;
  plan: PackingPlan | null;
  stale: boolean;
  explanations: string[];
  items: { garment: Garment; status: "TO_PACK" | "PACKED" }[];
  constraintPieces: ConstraintPiece[];
  schedule: {
    index: number;
    date: string;
    name: string;
    occasionTag: string | null;
    formality: string | null;
    laundryPeriod: number;
  }[];
  createdAt: string;
  updatedAt: string;
};
export type PackingSummary = Pick<
  PackingList,
  "id" | "name" | "startDate" | "endDate" | "locationText" | "version" | "createdAt"
> & {
  status: PackingPlan["status"] | null;
  itemCount: number;
  packedCount: number;
};
export type PackingPage = { items: PackingSummary[]; nextCursor: string | null };

export function tripDays(start: string, end: string) {
  return (
    Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000) + 1
  );
}
export function initialTrip(today = new Date().toISOString().slice(0, 10)): Trip {
  return {
    name: "",
    startDate: today,
    endDate: today,
    locationText: null,
    constraints: {
      maximumGarments: 8,
      weather: {
        minimumTemperatureC: null,
        maximumTemperatureC: null,
        assumptions: [],
        season: null,
      },
      occasions: [{ name: "Everyday", occasionTag: null, formality: null, days: 1 }],
      formalEvents: [],
      laundryEveryDays: 0,
      maximumWearsBetweenLaundry: 2,
      requiredGarments: [],
      excludedGarments: [],
    },
  };
}
export function tripInput(list: Trip): Trip {
  return {
    name: list.name,
    startDate: list.startDate,
    endDate: list.endDate,
    locationText: list.locationText,
    constraints: list.constraints,
  };
}
export function replacePackedPiece(
  plan: PackingPlan,
  previous: string,
  replacement: string,
): PackingPlan {
  return {
    status: "FEASIBLE",
    selectedGarments: [
      ...new Set(plan.selectedGarments.map((id) => (id === previous ? replacement : id))),
    ],
    outfits: plan.outfits.map((outfit) => ({
      ...outfit,
      garmentIds: [...new Set(outfit.garmentIds.map((id) => (id === previous ? replacement : id)))],
    })),
    warnings: [],
  };
}
