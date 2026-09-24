import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useGraphLayout } from "./use-graph-layout";
import type { LayoutInput } from "./types";

class LayoutWorker {
  static instances: LayoutWorker[] = [];
  onmessage?: (event: MessageEvent) => void;
  onerror?: () => void;
  postMessage = vi.fn();
  terminate = vi.fn();
  constructor() {
    LayoutWorker.instances.push(this);
  }
  reply(data: unknown) {
    act(() => this.onmessage?.({ data } as MessageEvent));
  }
}
const first: LayoutInput = { nodes: [{ id: "a" }], edges: [] };
beforeEach(() => {
  LayoutWorker.instances = [];
  vi.stubGlobal("Worker", LayoutWorker);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

it("runs layout in a worker, ignores obsolete replies, and terminates unused work", () => {
  const view = renderHook(({ input }) => useGraphLayout(input), { initialProps: { input: first } });
  const old = LayoutWorker.instances[0];
  expect(old.postMessage).toHaveBeenCalledWith({ key: JSON.stringify(first), input: first });
  const second = { nodes: [{ id: "b" }], edges: [] };
  view.rerender({ input: second });
  expect(old.terminate).toHaveBeenCalled();
  old.reply({ key: JSON.stringify(first), positions: [{ id: "a", x: 0, y: 0, z: 0 }] });
  expect(view.result.current).toBeUndefined();
  const worker = LayoutWorker.instances[1];
  worker.reply({ key: "wrong", positions: [] });
  expect(view.result.current).toBeUndefined();
  worker.reply({ key: JSON.stringify(second), positions: [{ id: "b", x: 0, y: 0, z: 0 }] });
  expect(view.result.current?.positions?.[0].id).toBe("b");
  expect(worker.terminate).toHaveBeenCalled();
  view.unmount();
});

it("retains settled layout when metrics and image URLs change", () => {
  const view = renderHook(({ input }) => useGraphLayout(input), { initialProps: { input: first } });
  LayoutWorker.instances[0].reply({
    key: JSON.stringify(first),
    positions: [{ id: "a", x: 0, y: 0, z: 0 }],
  });
  const refreshed = { nodes: [{ id: "a", wearCount: 5, assets: "refreshed" }], edges: [] };
  view.rerender({ input: refreshed });
  expect(LayoutWorker.instances).toHaveLength(1);
  expect(view.result.current?.positions).toHaveLength(1);
});

it("exposes an accessible fallback when workers fail or time out", () => {
  vi.useFakeTimers();
  const view = renderHook(() => useGraphLayout(first));
  act(() => vi.advanceTimersByTime(30_000));
  expect(view.result.current?.error).toContain("Browse the pieces below");
  expect(LayoutWorker.instances[0].terminate).toHaveBeenCalled();
  view.unmount();
  const failure = renderHook(() => useGraphLayout(first));
  act(() => LayoutWorker.instances[1].onerror?.());
  expect(failure.result.current?.error).toContain("Browse the pieces below");
});
