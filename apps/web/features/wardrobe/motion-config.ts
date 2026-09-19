export const WardrobeMotionConfig = {
  spring: { stiffness: 180, damping: 22, mass: 1.1 },
  itemSpacing: 210,
  mobileItemSpacing: 156,
  maximumRotation: 12,
  velocityToRotation: 0.008,
  snapThreshold: 0.25,
  dragThreshold: 8,
  flingThreshold: 500,
  velocityProjectionSeconds: 0.18,
  maximumFlingItems: 6,
  visibleRadius: 3,
  inactiveScaleStep: 0.09,
  minimumScale: 0.72,
  wheelThreshold: 70,
  wheelCooldownMs: 220,
  reducedMotionDuration: 0.12,
} as const;

export function targetIndex(
  current: number,
  count: number,
  offset: number,
  velocity: number,
  spacing: number,
  reducedMotion: boolean,
) {
  if (count === 0) return 0;
  const config = WardrobeMotionConfig;
  const projected =
    reducedMotion || Math.abs(velocity) < config.flingThreshold
      ? offset
      : offset + velocity * config.velocityProjectionSeconds;
  const movement =
    Math.abs(projected) < spacing * config.snapThreshold
      ? 0
      : Math.sign(-projected) *
        Math.max(1, Math.min(config.maximumFlingItems, Math.round(Math.abs(projected) / spacing)));
  return Math.max(0, Math.min(count - 1, current + movement));
}
