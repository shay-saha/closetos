export type SmartRule = {
  field: string;
  operator: string;
  value?: string | number | (string | number)[];
};
export type SmartQuery =
  SmartRule | { all: SmartQuery[] } | { any: SmartQuery[] } | { not: SmartQuery };
export type Collection = {
  id: string;
  name: string;
  type: "MANUAL" | "SMART";
  queryDefinition: SmartQuery | null;
  garmentIds: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
};
export type CollectionPage = { items: Collection[]; nextCursor: string | null };

export const smartViews = [
  ["RECENTLY_ADDED", "Recently added"],
  ["RECENTLY_WORN", "Recently worn"],
  ["MOST_WORN", "Most worn"],
  ["LEAST_WORN", "Least worn"],
  ["NEVER_WORN", "Never worn"],
  ["FORGOTTEN", "Forgotten"],
  ["BEST_COST_PER_WEAR", "Best cost per wear"],
  ["HIGHEST_COST_PER_WEAR", "Highest cost per wear"],
  ["CURRENT_SEASON", "Current season"],
  ["GOING_OUT", "Going out"],
  ["WORK", "Work"],
  ["FORMAL", "Formal"],
  ["PACKED", "Packed"],
  ["LAUNDRY", "Laundry"],
  ["ARCHIVED", "Archived"],
] as const;

export const emptyRule = (): SmartRule => ({ field: "wearCount", operator: "EQ", value: 0 });
