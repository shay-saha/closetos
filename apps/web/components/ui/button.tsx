"use client";

import { Button as BaseButton } from "@base-ui/react/button";

export function Button({
  className = "",
  variant = "primary",
  ...props
}: React.ComponentProps<typeof BaseButton> & {
  variant?: "primary" | "secondary" | "quiet" | "danger";
}) {
  return <BaseButton className={`button button-${variant} ${className}`} {...props} />;
}
