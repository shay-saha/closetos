"use client";

import { Button } from "@/components/ui/button";
import { categories, categoryNames } from "@/features/garments/types";
import { emptyRule, type SmartQuery, type SmartRule } from "./types";

type Kind = "text" | "integer" | "money" | "date" | "tags" | "category" | "status" | "processing";
const fields: Record<string, { label: string; kind: Kind; max?: number }> = {
  name: { label: "Name", kind: "text", max: 160 },
  category: { label: "Category", kind: "category" },
  status: { label: "Availability", kind: "status" },
  processingStatus: { label: "Photo processing", kind: "processing" },
  subcategory: { label: "Subcategory", kind: "text", max: 80 },
  colour: { label: "Colour", kind: "text", max: 60 },
  brand: { label: "Brand", kind: "text", max: 120 },
  size: { label: "Size", kind: "text", max: 40 },
  formality: { label: "Formality", kind: "text", max: 60 },
  season: { label: "Season", kind: "tags", max: 60 },
  occasion: { label: "Occasion", kind: "tags", max: 60 },
  style: { label: "Style tag", kind: "tags", max: 60 },
  wearCount: { label: "Wear count", kind: "integer" },
  ownedDays: { label: "Days since added", kind: "integer" },
  daysSinceLastWorn: { label: "Days since last worn", kind: "integer" },
  purchasePrice: { label: "Purchase price", kind: "money" },
  costPerWear: { label: "Cost per wear", kind: "money" },
  purchaseDate: { label: "Purchase date", kind: "date" },
  createdDate: { label: "Date added", kind: "date" },
  lastWornDate: { label: "Last worn date", kind: "date" },
};
const operatorLabels: Record<string, string> = {
  EQ: "equals",
  NE: "does not equal",
  GT: "greater than",
  GTE: "at least",
  LT: "less than",
  LTE: "at most",
  CONTAINS: "contains",
  NOT_CONTAINS: "does not contain",
  CONTAINS_CURRENT: "matches current season",
  IN: "is one of",
  NOT_IN: "is none of",
  IS_NULL: "is not recorded",
  IS_NOT_NULL: "is recorded",
};
const noValue = new Set(["IS_NULL", "IS_NOT_NULL", "CONTAINS_CURRENT"]);
function operators(kind: Kind, field: string) {
  if (kind === "tags")
    return field === "season"
      ? ["CONTAINS", "NOT_CONTAINS", "CONTAINS_CURRENT"]
      : ["CONTAINS", "NOT_CONTAINS"];
  const result = ["EQ", "NE", "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL"];
  if (["integer", "money", "date"].includes(kind)) result.push("GT", "GTE", "LT", "LTE");
  if (kind === "text") result.push("CONTAINS");
  return result;
}
function defaultValue(kind: Kind): string | number {
  if (kind === "integer" || kind === "money") return 0;
  if (kind === "category") return "TOP";
  if (kind === "status") return "AVAILABLE";
  if (kind === "processing") return "READY";
  return "";
}

function Condition({ rule, onChange }: { rule: SmartRule; onChange: (rule: SmartRule) => void }) {
  const field = fields[rule.field];
  const kind = field.kind;
  const isList = ["IN", "NOT_IN"].includes(rule.operator);
  const numeric = kind === "integer" || kind === "money";
  const options =
    kind === "category"
      ? categories
      : kind === "status"
        ? ["AVAILABLE", "LAUNDRY", "PACKED", "LENT", "ARCHIVED"]
        : kind === "processing"
          ? [
              "DRAFT",
              "AWAITING_UPLOAD",
              "UPLOADED",
              "PROCESSING_MEDIA",
              "ANALYSING",
              "READY_FOR_REVIEW",
              "READY",
              "FAILED",
            ]
          : null;
  return (
    <div className="smart-condition">
      <label>
        Field
        <select
          value={rule.field}
          onChange={(event) => {
            const name = event.target.value;
            const nextKind = fields[name].kind;
            onChange({
              field: name,
              operator: nextKind === "tags" ? "CONTAINS" : "EQ",
              value: defaultValue(nextKind),
            });
          }}
        >
          {Object.entries(fields).map(([name, value]) => (
            <option key={name} value={name}>
              {value.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Comparison
        <select
          value={rule.operator}
          onChange={(event) => {
            const operator = event.target.value;
            onChange({
              field: rule.field,
              operator,
              ...(noValue.has(operator)
                ? {}
                : {
                    value: ["IN", "NOT_IN"].includes(operator)
                      ? [defaultValue(kind)]
                      : defaultValue(kind),
                  }),
            });
          }}
        >
          {operators(kind, rule.field).map((operator) => (
            <option key={operator} value={operator}>
              {operatorLabels[operator]}
            </option>
          ))}
        </select>
      </label>
      {!noValue.has(rule.operator) && (
        <label>
          {isList ? "Values, separated by commas" : "Value"}
          {options && !isList ? (
            <select
              value={String(rule.value ?? "")}
              onChange={(event) => onChange({ ...rule, value: event.target.value })}
            >
              {options.map((option) => (
                <option key={option} value={option}>
                  {kind === "category"
                    ? categoryNames[option as keyof typeof categoryNames]
                    : option.toLowerCase().replaceAll("_", " ")}
                </option>
              ))}
            </select>
          ) : (
            <input
              required
              type={isList ? "text" : numeric ? "number" : kind === "date" ? "date" : "text"}
              min={numeric && !isList ? 0 : undefined}
              step={kind === "money" ? "0.01" : 1}
              max={
                numeric && !isList ? (kind === "integer" ? 2147483647 : 9999999999.99) : undefined
              }
              maxLength={isList ? 8000 : field.max}
              value={Array.isArray(rule.value) ? rule.value.join(", ") : (rule.value ?? "")}
              onChange={(event) => {
                const raw = event.target.value;
                const value = isList
                  ? raw
                      .split(",")
                      .map((item) => (numeric && item.trim() ? Number(item.trim()) : item.trim()))
                  : numeric && raw
                    ? Number(raw)
                    : raw;
                onChange({ ...rule, value });
              }}
            />
          )}
        </label>
      )}
      {rule.operator === "CONTAINS_CURRENT" && (
        <p className="small">
          Northern seasons: spring March–May, summer June–August, autumn September–November, winter
          December–February. All-season tags also match.
        </p>
      )}
    </div>
  );
}

export function RuleBuilder({
  node,
  onChange,
  depth = 0,
}: {
  node: SmartQuery;
  onChange: (node: SmartQuery) => void;
  depth?: number;
}) {
  if ("field" in node) return <Condition rule={node} onChange={onChange} />;
  if ("not" in node)
    return (
      <fieldset className="smart-group">
        <legend>Exclude matching pieces</legend>
        <RuleBuilder
          node={node.not}
          depth={depth + 1}
          onChange={(next) => onChange({ not: next })}
        />
      </fieldset>
    );
  const mode = "all" in node ? "all" : "any";
  const children = "all" in node ? node.all : node.any;
  function replace(next: SmartQuery[]) {
    onChange(mode === "all" ? { all: next } : { any: next });
  }
  return (
    <fieldset className="smart-group">
      <legend>Rule group</legend>
      <label>
        Match
        <select
          value={mode}
          onChange={(event) =>
            onChange(event.target.value === "all" ? { all: children } : { any: children })
          }
        >
          <option value="all">All these conditions</option>
          <option value="any">Any of these conditions</option>
        </select>
      </label>
      {children.map((child, index) => (
        <div className="smart-rule-row" key={index}>
          <RuleBuilder
            node={child}
            depth={depth + 1}
            onChange={(next) =>
              replace(children.map((item, position) => (position === index ? next : item)))
            }
          />
          <Button
            variant="quiet"
            type="button"
            disabled={children.length === 1}
            aria-label={`Remove condition ${index + 1}`}
            onClick={() => replace(children.filter((_, position) => position !== index))}
          >
            Remove
          </Button>
        </div>
      ))}
      <div className="form-actions">
        <Button
          variant="secondary"
          type="button"
          disabled={children.length >= 50}
          onClick={() => replace([...children, emptyRule()])}
        >
          Add condition
        </Button>
        <Button
          variant="secondary"
          type="button"
          disabled={depth >= 7 || children.length >= 50}
          onClick={() => replace([...children, { any: [emptyRule()] }])}
        >
          Add group
        </Button>
        <Button
          variant="quiet"
          type="button"
          disabled={depth >= 7 || children.length >= 50}
          onClick={() => replace([...children, { not: emptyRule() }])}
        >
          Add exclusion
        </Button>
      </div>
    </fieldset>
  );
}
