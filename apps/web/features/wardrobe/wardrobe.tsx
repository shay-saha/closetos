"use client";

import { animate, motion, useMotionValue, useReducedMotion } from "motion/react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Shirt } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { useGarments } from "@/features/garments/queries";
import { categories, categoryNames, type Category } from "@/features/garments/types";
import { WardrobeMotionConfig as config, targetIndex } from "./motion-config";
import { RailPiece } from "./rail-piece";

export function Wardrobe() {
  const params = useSearchParams();
  const router = useRouter();
  const category = categories.includes(params.get("category") as Category)
    ? (params.get("category") as Category)
    : "TOP";
  const query = useGarments(`category=${category}`);
  const garments = useMemo(
    () => query.data?.pages.flatMap((page) => page.items) ?? [],
    [query.data],
  );
  const selectedId = params.get(`focus.${category}`);
  const index = Math.max(
    0,
    garments.findIndex((garment) => garment.id === selectedId),
  );
  const active = garments[index];
  const drag = useMotionValue(0);
  const velocity = useMotionValue(0);
  const reduced = !!useReducedMotion();
  const [spacing, setSpacing] = useState<number>(config.itemSpacing);
  const wheel = useRef({ sum: 0, movedAt: 0 });
  const didDrag = useRef(false);
  useEffect(() => {
    const media = window.matchMedia("(max-width: 700px)");
    const update = () => setSpacing(media.matches ? config.mobileItemSpacing : config.itemSpacing);
    update();
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);
  useEffect(() => {
    if (
      query.hasNextPage &&
      !query.isFetchingNextPage &&
      (index >= garments.length - 8 ||
        (selectedId && !garments.some((garment) => garment.id === selectedId)))
    ) {
      void query.fetchNextPage();
    }
  }, [query, garments, index, selectedId]);
  function navigate(nextIndex: number) {
    const garment = garments[Math.max(0, Math.min(garments.length - 1, nextIndex))];
    if (!garment) return;
    const next = new URLSearchParams(params);
    next.set(`focus.${category}`, garment.id);
    window.history.replaceState(null, "", `/wardrobe?${next}`);
  }
  function switchCategory(nextCategory: Category) {
    drag.set(0);
    velocity.set(0);
    const next = new URLSearchParams(params);
    next.set("category", nextCategory);
    window.history.replaceState(null, "", `/wardrobe?${next}`);
  }
  const storage =
    category === "JEWELLERY" || category === "ACCESSORY"
      ? "tray"
      : ["SHOES", "BAG", "OTHER"].includes(category)
        ? "shelf"
        : "hanger";
  return (
    <div className="page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">The everyday, rediscovered</p>
          <h1>Inside your wardrobe.</h1>
          <p>Move through your pieces. Pause on something you love.</p>
        </div>
        <Link href="/catalogue" className="button button-secondary">
          View catalogue
        </Link>
      </div>
      <div className="category-tabs" aria-label="Garment categories">
        {categories.map((value) => (
          <Button
            key={value}
            variant="quiet"
            aria-pressed={category === value}
            onClick={() => switchCategory(value)}
          >
            {categoryNames[value]}
          </Button>
        ))}
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : !active ? (
        <div className="empty-state">
          <Shirt size={48} strokeWidth={1} />
          <h2>A little space for possibility.</h2>
          <p>You haven’t added any {categoryNames[category].toLowerCase()} yet.</p>
          <Link href="/add" className="button button-primary">
            Add your first piece
          </Link>
        </div>
      ) : (
        <>
          <motion.div
            className="rail-space"
            data-storage={storage}
            role="listbox"
            aria-label={`${categoryNames[category]} wardrobe. Use left and right arrows to browse, Enter to open.`}
            aria-activedescendant={`piece-${active.id}`}
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
                event.preventDefault();
                navigate(index + (event.key === "ArrowLeft" ? -1 : 1));
              }
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                router.push(`/garments/${active.id}`);
              }
            }}
            onWheel={(event) => {
              if (Math.abs(event.deltaX) <= Math.abs(event.deltaY)) return;
              if (Date.now() - wheel.current.movedAt < config.wheelCooldownMs) return;
              wheel.current.sum += event.deltaX;
              if (Math.abs(wheel.current.sum) >= config.wheelThreshold) {
                navigate(index + Math.sign(wheel.current.sum));
                wheel.current = { sum: 0, movedAt: Date.now() };
              }
            }}
            onPanStart={() => {
              didDrag.current = false;
            }}
            onPan={(_, info) => {
              didDrag.current = Math.abs(info.offset.x) > config.dragThreshold;
              drag.set(info.offset.x);
              velocity.set(info.velocity.x);
            }}
            onPanEnd={(_, info) => {
              navigate(
                targetIndex(
                  index,
                  garments.length,
                  info.offset.x,
                  info.velocity.x,
                  spacing,
                  reduced,
                ),
              );
              const transition = reduced
                ? { duration: config.reducedMotionDuration }
                : { type: "spring" as const, ...config.spring };
              animate(drag, 0, transition);
              animate(velocity, 0, transition);
              window.setTimeout(() => {
                didDrag.current = false;
              }, 0);
            }}
          >
            <div className="rail-line" aria-hidden="true" />
            {garments
              .slice(Math.max(0, index - config.visibleRadius), index + config.visibleRadius + 1)
              .map((garment) => {
                const position = garments.indexOf(garment);
                return (
                  <RailPiece
                    key={garment.id}
                    garment={garment}
                    index={position}
                    count={query.hasNextPage ? -1 : garments.length}
                    distance={position - index}
                    spacing={spacing}
                    drag={drag}
                    velocity={velocity}
                    reduced={reduced}
                    select={() => {
                      if (didDrag.current) return;
                      if (position === index) router.push(`/garments/${garment.id}`);
                      else navigate(position);
                    }}
                  />
                );
              })}
          </motion.div>
          <div className="rail-controls">
            <Button
              variant="quiet"
              aria-label="Previous piece"
              disabled={index === 0}
              onClick={() => navigate(index - 1)}
            >
              <ChevronLeft size={20} />
            </Button>
            <span className="small" aria-live="polite">
              {index + 1} of {garments.length}
              {query.hasNextPage ? "+" : ""}
            </span>
            <Button
              variant="quiet"
              aria-label="Next piece"
              disabled={index === garments.length - 1 && !query.hasNextPage}
              onClick={() => navigate(index + 1)}
            >
              <ChevronRight size={20} />
            </Button>
          </div>
          <div className="rail-selected">
            <h2>{active.name}</h2>
            <p>
              {[active.primaryColourName, active.material, active.brand]
                .filter(Boolean)
                .join(" · ") || categoryNames[active.category]}
            </p>
            <Link href={`/garments/${active.id}`} className="button button-secondary">
              Take a closer look
            </Link>
          </div>
        </>
      )}
    </div>
  );
}
