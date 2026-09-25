import { describe, expect, it } from "vitest";
import {
  initialTrip,
  replacePackedPiece,
  tripDays,
  tripInput,
  tripSchema,
  type PackingPlan,
} from "./types";

const first = "00000000-0000-4000-8000-000000000001";
const second = "00000000-0000-4000-8000-000000000002";
const third = "00000000-0000-4000-8000-000000000003";
function trip() {
  const value = initialTrip("2026-03-28");
  value.name = "Spring weekend";
  value.endDate = "2026-03-30";
  value.constraints.occasions[0].days = 3;
  value.constraints.weather.assumptions = ["MILD"];
  return value;
}

describe("packing trip validation", () => {
  it("counts calendar days across a daylight-saving transition and requires full occasion coverage", () => {
    expect(tripDays("2026-03-28", "2026-03-30")).toBe(3);
    expect(tripSchema.safeParse(trip()).success).toBe(true);
    const incomplete = trip();
    incomplete.constraints.occasions[0].days = 2;
    expect(tripSchema.safeParse(incomplete).success).toBe(false);
  });
  it("rejects nonexistent, reversed, and oversized date ranges", () => {
    for (const endDate of ["2026-02-30", "2026-03-27", "2026-05-01"])
      expect(tripSchema.safeParse({ ...trip(), endDate }).success).toBe(false);
  });
  it("requires stated weather and ordered complete temperature bounds", () => {
    const value = trip();
    value.constraints.weather.assumptions = [];
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.weather.minimumTemperatureC = 10;
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.weather.maximumTemperatureC = 9;
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.weather.maximumTemperatureC = 15;
    expect(tripSchema.safeParse(value).success).toBe(true);
  });
  it("requires formal events to have a stated formality and occur during the trip", () => {
    const value = trip();
    value.constraints.formalEvents = [
      { name: "Dinner", date: "2026-03-31", occasionTag: null, formality: "Formal" },
    ];
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.formalEvents[0].date = "2026-03-29";
    expect(tripSchema.safeParse(value).success).toBe(true);
    value.constraints.formalEvents[0].formality = " ";
    expect(tripSchema.safeParse(value).success).toBe(false);
  });
  it("rejects overlapping, repeated, and invalid required/excluded references", () => {
    const value = trip();
    value.constraints.requiredGarments = [first];
    value.constraints.excludedGarments = [first];
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.excludedGarments = [second];
    expect(tripSchema.safeParse(value).success).toBe(true);
    value.constraints.requiredGarments = [first, first];
    expect(tripSchema.safeParse(value).success).toBe(false);
    value.constraints.requiredGarments = ["not-a-piece"];
    expect(tripSchema.safeParse(value).success).toBe(false);
  });
  it("keeps impossible count requests valid so the solver can explain infeasibility", () => {
    const value = trip();
    value.constraints.maximumGarments = 1;
    value.constraints.requiredGarments = [first, second];
    expect(tripSchema.safeParse(value).success).toBe(true);
    expect(Object.keys(tripInput({ ...value, version: 8 } as typeof value))).not.toContain(
      "version",
    );
  });
});

describe("manual capsule swaps", () => {
  it("replaces the piece in every scheduled outfit and removes the previous optimum claim", () => {
    const plan: PackingPlan = {
      status: "OPTIMAL",
      selectedGarments: [first, second],
      outfits: [
        { demandIndex: 0, garmentIds: [first, second] },
        { demandIndex: 1, garmentIds: [first, second] },
      ],
      warnings: [],
    };
    const replacement = replacePackedPiece(plan, first, third);
    expect(replacement.status).toBe("FEASIBLE");
    expect(replacement.selectedGarments).toEqual([third, second]);
    expect(replacement.outfits.map((outfit) => outfit.garmentIds)).toEqual([
      [third, second],
      [third, second],
    ]);
    expect(plan.selectedGarments).toEqual([first, second]);
  });
});
