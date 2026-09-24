import { expect, it } from "vitest";
import { layoutGraph } from "./force-layout";
import type { GraphPosition, LayoutInput } from "./types";

function distance(a: GraphPosition, b: GraphPosition) {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}
const input: LayoutInput = {
  nodes: ["a", "b", "c", "d", "e", "f"].map((id) => ({ id })),
  edges: [
    ["a", "b"],
    ["b", "c"],
    ["a", "c"],
    ["d", "e"],
    ["e", "f"],
    ["d", "f"],
  ].map(([source, target]) => ({ source, target, weight: 0.95 })),
};

it("places related pieces closer than separate clusters without mutating the API graph", () => {
  const original = structuredClone(input);
  const result = layoutGraph(input);
  expect(input).toEqual(original);
  expect(result).toEqual(layoutGraph(input));
  expect(result.map((node) => node.id)).toEqual(input.nodes.map((node) => node.id));
  const within = (distance(result[0], result[1]) + distance(result[3], result[4])) / 2;
  const between = (distance(result[0], result[3]) + distance(result[1], result[4])) / 2;
  expect(within).toBeLessThan(between);
  expect(result.every((node) => Math.hypot(node.x, node.y, node.z) <= 12.000001)).toBe(true);
});

it("handles empty and single-piece wardrobes without invented relationships", () => {
  expect(layoutGraph({ nodes: [], edges: [] })).toEqual([]);
  expect(layoutGraph({ nodes: [{ id: "one" }], edges: [] })).toEqual([
    { id: "one", x: 0, y: 0, z: 0 },
  ]);
});

it("rejects dangling, self, duplicate, and non-finite graph inputs", () => {
  for (const graph of [
    { nodes: [{ id: "a" }, { id: "a" }], edges: [] },
    { nodes: [{ id: "a" }], edges: [{ source: "a", target: "b", weight: 0.5 }] },
    { nodes: [{ id: "a" }], edges: [{ source: "a", target: "a", weight: 0.5 }] },
    { ...input, edges: [{ source: "a", target: "b", weight: Number.NaN }] },
    { ...input, edges: [{ source: "a", target: "b", weight: 2 }] },
    { nodes: Array.from({ length: 2001 }, (_, n) => ({ id: String(n) })), edges: [] },
  ])
    expect(() => layoutGraph(graph)).toThrow();
});

it("settles a representative thousand-piece graph within a bounded time and space", () => {
  const nodes = Array.from({ length: 1000 }, (_, n) => ({ id: String(n) }));
  const edges = nodes.flatMap((node, n) =>
    [1, 2, 3].map((offset) => ({
      source: node.id,
      target: String((n + offset) % nodes.length),
      weight: 0.85,
    })),
  );
  const started = performance.now();
  const result = layoutGraph({ nodes, edges });
  const elapsed = performance.now() - started;
  console.info(`Force layout over 1000 pieces: ${elapsed.toFixed(1)} ms`);
  expect(result).toHaveLength(1000);
  expect(new Set(result.map((node) => node.id)).size).toBe(1000);
  expect(
    result.every(
      (node) =>
        [node.x, node.y, node.z].every(Number.isFinite) &&
        Math.hypot(node.x, node.y, node.z) <= 12.000001,
    ),
  ).toBe(true);
  expect(elapsed).toBeLessThan(8000);
}, 15_000);
