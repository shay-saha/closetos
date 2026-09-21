"use client";

import { useRef } from "react";
import { GarmentArt } from "@/features/garments/garment-art";
import type { Garment } from "@/features/garments/types";
import { moveItem, type OutfitItem } from "./types";

export function OutfitCanvas({
  items,
  garments,
  selected,
  select,
  change,
  add,
}: {
  items: OutfitItem[];
  garments: Map<string, Garment>;
  selected?: string;
  select?: (id: string) => void;
  change?: (item: OutfitItem) => void;
  add?: (id: string, x: number, y: number) => void;
}) {
  const canvas = useRef<HTMLDivElement>(null);
  const drag = useRef<{ item: OutfitItem; x: number; y: number }>(undefined);
  return (
    <div
      className="outfit-canvas"
      role="group"
      aria-label="Outfit canvas"
      ref={canvas}
      onDragOver={add ? (event) => event.preventDefault() : undefined}
      onDrop={
        add
          ? (event) => {
              event.preventDefault();
              const id = event.dataTransfer.getData("application/closetos-garment");
              if (!garments.has(id)) return;
              const bounds = event.currentTarget.getBoundingClientRect();
              add(
                id,
                (100 * (event.clientX - bounds.left)) / bounds.width,
                (100 * (event.clientY - bounds.top)) / bounds.height,
              );
            }
          : undefined
      }
    >
      {!items.length && <p className="canvas-empty">Choose pieces from your drawer to begin.</p>}
      {items.map((item) => {
        const garment = garments.get(item.garmentId);
        const style = {
          left: `${item.x}%`,
          top: `${item.y}%`,
          zIndex: item.zIndex,
          transform: `translate(-50%, -50%) scale(${item.scale}) rotate(${item.rotation}deg)`,
        };
        const art = <GarmentArt garment={garment ?? { category: "OTHER" }} size="display" />;
        return change && select ? (
          <button
            type="button"
            key={item.garmentId}
            className="canvas-piece"
            style={style}
            aria-label={`Position ${garment?.name ?? "piece"}`}
            aria-pressed={selected === item.garmentId}
            onClick={() => select(item.garmentId)}
            onPointerDown={(event) => {
              if (event.button !== 0) return;
              select(item.garmentId);
              drag.current = { item, x: event.clientX, y: event.clientY };
              event.currentTarget.setPointerCapture(event.pointerId);
            }}
            onPointerMove={(event) => {
              if (!drag.current || !canvas.current) return;
              const bounds = canvas.current.getBoundingClientRect();
              change(
                moveItem(
                  drag.current.item,
                  drag.current.item.x + (100 * (event.clientX - drag.current.x)) / bounds.width,
                  drag.current.item.y + (100 * (event.clientY - drag.current.y)) / bounds.height,
                ),
              );
            }}
            onPointerUp={() => {
              drag.current = undefined;
            }}
            onPointerCancel={() => {
              drag.current = undefined;
            }}
            onKeyDown={(event) => {
              const movement: Record<string, [number, number]> = {
                ArrowLeft: [-1, 0],
                ArrowRight: [1, 0],
                ArrowUp: [0, -1],
                ArrowDown: [0, 1],
              };
              const delta = movement[event.key];
              if (!delta) return;
              event.preventDefault();
              select(item.garmentId);
              const step = event.shiftKey ? 5 : 1;
              change(moveItem(item, item.x + delta[0] * step, item.y + delta[1] * step));
            }}
          >
            {art}
          </button>
        ) : (
          <div key={item.garmentId} className="canvas-piece" style={style}>
            {art}
          </div>
        );
      })}
    </div>
  );
}
