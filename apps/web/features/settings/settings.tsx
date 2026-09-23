"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { ReembeddingAdmin } from "@/features/search/reembedding-admin";
import { api } from "@/lib/api";

const preferencesSchema = z.object({
  displayName: z.string().trim().min(1, "Enter your display name.").max(120),
  locale: z.string().trim().min(1).max(35),
  currency: z.string().regex(/^[A-Z]{3}$/, "Use a three-letter currency code, such as GBP."),
  timezone: z.string().trim().min(1).max(80),
  deleteOriginalAfterIsolation: z.boolean(),
});
type Preferences = z.infer<typeof preferencesSchema>;
type Profile = Preferences & { id: string; version: number };

function PreferencesForm({
  profile,
  save,
  pending,
  error,
}: {
  profile: Profile;
  save: (values: Preferences) => void;
  pending: boolean;
  error: Error | null;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Preferences>({
    resolver: zodResolver(preferencesSchema),
    defaultValues: profile,
  });
  return (
    <form className="garment-form settings-form" onSubmit={handleSubmit(save)}>
      {(
        [
          ["displayName", "Display name", "Your name"],
          ["locale", "Locale", "en-GB"],
          ["currency", "Currency", "GBP"],
          ["timezone", "Timezone", "Europe/London"],
        ] as const
      ).map(([name, label, placeholder]) => (
        <label key={name}>
          {label}
          <input
            {...register(name)}
            placeholder={placeholder}
            aria-invalid={!!errors[name]}
            aria-describedby={errors[name] ? `${name}-error` : undefined}
          />
          {errors[name] && (
            <span id={`${name}-error`} className="field-error">
              {errors[name]?.message}
            </span>
          )}
        </label>
      ))}
      <fieldset className="privacy-preference">
        <legend>Photograph privacy</legend>
        <label className="checkbox-field">
          <input
            type="checkbox"
            {...register("deleteOriginalAfterIsolation")}
            aria-describedby="privacy-explanation"
          />
          Delete original photographs after background removal
        </label>
        <p id="privacy-explanation" className="small">
          Applies to new uploads. The original is deleted only after the isolated images are
          verified. Your isolated photographs remain available; existing originals are unchanged.
        </p>
      </fieldset>
      {error && (
        <p role="alert" className="field-error">
          {error.message}
        </p>
      )}
      <Button type="submit" disabled={pending}>
        {pending ? "Saving preferences…" : "Save preferences"}
      </Button>
    </form>
  );
}

export function Settings() {
  const client = useQueryClient();
  const profile = useQuery({
    queryKey: ["profile"],
    queryFn: ({ signal }) => api<Profile>("me", { signal }),
  });
  const save = useMutation({
    mutationFn: (preferences: Preferences) =>
      api<Profile>("me", {
        method: "PATCH",
        body: JSON.stringify({ ...preferences, version: profile.data?.version }),
      }),
    onSuccess: (saved) => client.setQueryData(["profile"], saved),
  });
  if (profile.isPending)
    return (
      <div className="page">
        <LoadingState />
      </div>
    );
  if (profile.isError) return <ErrorState error={profile.error} retry={profile.refetch} />;
  return (
    <div className="page">
      <p className="eyebrow">Your wardrobe, your preferences</p>
      <h1>A few personal details.</h1>
      <p>Set your profile and choose how your original photographs are kept.</p>
      {save.isSuccess && (
        <p className="processing-notice" role="status">
          Your preferences are saved.
        </p>
      )}
      <PreferencesForm
        key={profile.data.version}
        profile={profile.data}
        save={save.mutate}
        pending={save.isPending}
        error={save.error}
      />
      <ReembeddingAdmin />
    </div>
  );
}
