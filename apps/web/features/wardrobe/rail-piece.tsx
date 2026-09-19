"use client";

import { animate, motion, useMotionValue, useTransform, type MotionValue } from "motion/react";
import { useEffect } from "react";
import type { Garment } from "@/features/garments/types";
import { GarmentArt } from "@/features/garments/garment-art";
import { WardrobeMotionConfig as config } from "./motion-config";

export function RailPiece({
  garment,
  index,
  count,
  distance,
  spacing,
  drag,
  velocity,
  reduced,
  select,
}: {
  garment: Garment;
  index: number;
  count: number;
  distance: number;
  spacing: number;
  drag: MotionValue<number>;
  velocity: MotionValue<number>;
  reduced: boolean;
  select: () => void;
}) {
  const base = useMotionValue(distance * spacing);
  const x = useTransform(() => base.get() + drag.get());
  const rotate = useTransform(() =>
    reduced
      ? 0
      : Math.max(
          -config.maximumRotation,
          Math.min(config.maximumRotation, velocity.get() * config.velocityToRotation),
        ),
  );
  useEffect(() => {
    const animation = animate(
      base,
      distance * spacing,
      reduced ? { duration: config.reducedMotionDuration } : { type: "spring", ...config.spring },
    );
    return () => animation.stop();
  }, [base, distance, spacing, reduced]);
  return (
    <motion.div
      className="rail-piece"
      style={{ x, rotate, zIndex: 10 - Math.abs(distance) }}
      animate={{
        scale: Math.max(config.minimumScale, 1 - Math.abs(distance) * config.inactiveScaleStep),
      }}
      transition={
        reduced ? { duration: config.reducedMotionDuration } : { type: "spring", ...config.spring }
      }
    >
      <button
        className="rail-option"
        id={`piece-${garment.id}`}
        role="option"
        aria-selected={distance === 0}
        aria-label={`${garment.name}, ${garment.status.toLowerCase()}`}
        aria-posinset={index + 1}
        aria-setsize={count}
        tabIndex={-1}
        onClick={select}
      >
        <GarmentArt garment={garment} hanger />
      </button>
    </motion.div>
  );
}
