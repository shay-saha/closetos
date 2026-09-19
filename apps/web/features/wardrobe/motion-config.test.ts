import { describe, expect, it } from "vitest";
import { targetIndex, WardrobeMotionConfig as config } from "./motion-config";

describe("wardrobe settling", () => {
  it("keeps accidental movement on the current piece", () => {
    expect(targetIndex(4, 20, -10, 10, config.itemSpacing, false)).toBe(4);
  });
  it("makes a fast flick travel further than a slow swipe", () => {
    const slow = targetIndex(4, 20, -100, -100, config.itemSpacing, false);
    const fast = targetIndex(4, 20, -100, -2200, config.itemSpacing, false);
    expect(slow).toBe(5);
    expect(fast).toBeGreaterThan(slow);
  });
  it("disables velocity projection for reduced motion", () => {
    expect(targetIndex(4, 20, -100, -2200, config.itemSpacing, true)).toBe(5);
  });
  it("clamps to the rail boundaries", () => {
    expect(targetIndex(0, 20, 1000, 3000, config.itemSpacing, false)).toBe(0);
    expect(targetIndex(19, 20, -1000, -3000, config.itemSpacing, false)).toBe(19);
    expect(targetIndex(0, 0, -100, -1000, config.itemSpacing, false)).toBe(0);
  });
  it("supports narrower touch spacing without changing the physics", () => {
    expect(targetIndex(4, 20, -100, -100, config.mobileItemSpacing, false)).toBe(5);
  });
});
