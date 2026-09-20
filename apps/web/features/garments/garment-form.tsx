"use client";

import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { categories, categoryNames, metadataSchema, type GarmentMetadata } from "./types";

export function GarmentForm({
  initial,
  submit,
  pending,
  error,
  cancel,
  submitLabel,
}: {
  initial?: GarmentMetadata;
  submit: (metadata: GarmentMetadata) => void;
  pending: boolean;
  error?: Error | null;
  cancel?: () => void;
  submitLabel?: string;
}) {
  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<GarmentMetadata>({
    resolver: zodResolver(metadataSchema),
    defaultValues: initial
      ? { ...initial, purchaseCurrency: initial.purchaseCurrency ?? "GBP" }
      : { name: "", category: "TOP", purchaseCurrency: "GBP" },
  });
  return (
    <form className="garment-form" onSubmit={handleSubmit(submit)}>
      <label>
        <span id="name-label">Piece name *</span>
        <input
          aria-labelledby="name-label"
          {...register("name")}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? "name-error" : undefined}
          autoComplete="off"
        />
        {errors.name && (
          <span className="field-error" id="name-error">
            {errors.name.message}
          </span>
        )}
      </label>
      <div className="field-row">
        <label>
          Category *
          <select {...register("category")}>
            {categories.map((category) => (
              <option key={category} value={category}>
                {categoryNames[category]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Subcategory
          <input {...register("subcategory")} placeholder="Knit, midi skirt, trainers…" />
        </label>
      </div>
      <div className="field-row">
        <label>
          Colour
          <input {...register("primaryColourName")} placeholder="Cream, indigo, olive…" />
        </label>
        <label>
          Colour swatch
          <input
            type="color"
            {...register("primaryColourHex")}
            defaultValue={initial?.primaryColourHex ?? "#a5a48f"}
          />
        </label>
      </div>
      <div className="field-row">
        <label>
          Brand
          <input {...register("brand")} />
        </label>
        <label>
          Size
          <input {...register("sizeLabel")} />
        </label>
      </div>
      <div className="field-row">
        <label>
          Material
          <input {...register("material")} placeholder="Cotton, linen, wool…" />
        </label>
        <label>
          Formality
          <input {...register("formality")} placeholder="Casual, work, formal…" />
        </label>
      </div>
      <div className="field-row">
        <label>
          Pattern
          <input {...register("pattern")} placeholder="Plain, striped, floral…" />
        </label>
        <label>
          Length
          <input {...register("length")} placeholder="Cropped, midi, full length…" />
        </label>
      </div>
      {(
        [
          ["secondaryColours", "Secondary colours"],
          ["seasonTags", "Seasons"],
          ["styleTags", "Style tags"],
          ["occasionTags", "Occasions"],
        ] as const
      ).map(([name, label]) => (
        <Controller
          key={name}
          name={name}
          control={control}
          render={({ field, fieldState }) => (
            <label>
              {label}
              <input
                name={field.name}
                ref={field.ref}
                onBlur={field.onBlur}
                defaultValue={field.value?.join(", ") ?? ""}
                onChange={(event) =>
                  field.onChange(
                    event.target.value
                      .split(",")
                      .map((tag) => tag.trim())
                      .filter(Boolean),
                  )
                }
                placeholder="Separate with commas"
                aria-invalid={!!fieldState.error}
              />
              {fieldState.error && <span className="field-error">{fieldState.error.message}</span>}
            </label>
          )}
        />
      ))}
      <div className="field-row">
        <label>
          Purchase price
          <input
            type="number"
            min="0"
            step="0.01"
            {...register("purchasePrice", {
              setValueAs: (value) => (value == null || value === "" ? null : Number(value)),
            })}
            aria-invalid={!!errors.purchasePrice}
          />
          {errors.purchasePrice && (
            <span className="field-error">{errors.purchasePrice.message}</span>
          )}
        </label>
        <label>
          Currency
          <select {...register("purchaseCurrency")}>
            <option>GBP</option>
            <option>USD</option>
            <option>EUR</option>
            <option>INR</option>
          </select>
          {errors.purchaseCurrency && (
            <span className="field-error">{errors.purchaseCurrency.message}</span>
          )}
        </label>
      </div>
      <label>
        Purchased on
        <input
          type="date"
          {...register("purchaseDate", { setValueAs: (value) => value || null })}
          max={new Date().toISOString().slice(0, 10)}
        />
      </label>
      <label>
        Notes
        <textarea
          {...register("notes")}
          rows={4}
          placeholder="The fit, the feeling, the story behind it."
        />
      </label>
      {error && (
        <p role="alert" className="field-error">
          {error.message}
        </p>
      )}
      {Object.keys(errors).some(
        (field) =>
          ![
            "name",
            "purchasePrice",
            "purchaseCurrency",
            "secondaryColours",
            "seasonTags",
            "styleTags",
            "occasionTags",
          ].includes(field),
      ) && (
        <p role="alert" className="field-error">
          Check the highlighted fields. Some details are too long or have an invalid format.
        </p>
      )}
      <div className="form-actions">
        <Button type="submit" disabled={pending}>
          {pending
            ? "Saving your piece…"
            : (submitLabel ?? (initial ? "Save changes" : "Add to your wardrobe"))}
        </Button>
        {cancel && (
          <Button type="button" variant="secondary" onClick={cancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}
