import { layoutGraph } from "./force-layout";
import type { LayoutInput } from "./types";

self.onmessage = (event: MessageEvent<{ key: string; input: LayoutInput }>) => {
  try {
    self.postMessage({ key: event.data.key, positions: layoutGraph(event.data.input) });
  } catch {
    self.postMessage({
      key: event.data.key,
      error: "The graph could not be arranged. Browse the pieces below.",
    });
  }
};
