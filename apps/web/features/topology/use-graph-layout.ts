"use client";

import { useEffect, useState } from "react";
import type { GraphPosition, LayoutInput } from "./types";

type LayoutResult = { key: string; positions?: GraphPosition[]; error?: string };

export function useGraphLayout(input: LayoutInput) {
  const key = JSON.stringify({ nodes: input.nodes.map(({ id }) => ({ id })), edges: input.edges });
  const [result, setResult] = useState<LayoutResult>();
  useEffect(() => {
    let active = true;
    let worker: Worker | undefined;
    const failed = () => {
      if (active)
        setResult({ key, error: "The graph could not be arranged. Browse the pieces below." });
    };
    const timeout = setTimeout(() => {
      failed();
      worker?.terminate();
    }, 30_000);
    try {
      worker = new Worker(new URL("./layout.worker.ts", import.meta.url), { type: "module" });
      worker.onmessage = (event: MessageEvent<LayoutResult>) => {
        if (!active || event.data.key !== key) return;
        clearTimeout(timeout);
        setResult(event.data);
        worker?.terminate();
      };
      worker.onerror = () => {
        clearTimeout(timeout);
        failed();
        worker?.terminate();
      };
      worker.postMessage({ key, input: JSON.parse(key) });
    } catch {
      clearTimeout(timeout);
      failed();
    }
    return () => {
      active = false;
      clearTimeout(timeout);
      worker?.terminate();
    };
  }, [key]);
  return result?.key === key ? result : undefined;
}
