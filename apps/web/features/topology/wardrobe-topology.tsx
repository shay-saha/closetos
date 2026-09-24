"use client";

import {
  Component,
  startTransition,
  useMemo,
  useOptimistic,
  useState,
  type ReactNode,
} from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronRight, Network } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState, LoadingState } from "@/components/ui/feedback";
import { GarmentArt } from "@/features/garments/garment-art";
import { categories, categoryNames } from "@/features/garments/types";
import { useTopology } from "./queries";
import { categoryColours, wearColours, wearLabels, type ColourMetric } from "./types";

const GraphCanvas = dynamic(() => import("./graph-canvas"), {
  ssr: false,
  loading: () => (
    <div className="topology-stage topology-loading" role="status">
      Opening your wardrobe map…
    </div>
  ),
});
class GraphBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <p className="processing-notice" role="status">
        The 3D view is unavailable here. Browse the pieces below.
      </p>
    ) : (
      this.props.children
    );
  }
}

export function WardrobeTopology() {
  const url = useSearchParams();
  const router = useRouter();
  const [draft, edit] = useOptimistic(
    url.toString(),
    (current, change: { key: string; value: string }) => {
      const next = new URLSearchParams(current);
      if (change.value) next.set(change.key, change.value);
      else next.delete(change.key);
      return next.toString();
    },
  );
  const params = useMemo(() => new URLSearchParams(draft), [draft]);
  const category = params.get("category") ?? "";
  const metric: ColourMetric = params.get("colour") === "wear" ? "wear" : "category";
  const listView = params.get("view") === "list";
  const showEdges = params.get("edges") !== "hidden";
  const query = useTopology(category);
  const graph = query.data;
  const nodes = useMemo(
    () =>
      [...(graph?.nodes ?? [])].sort(
        (a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id),
      ),
    [graph?.nodes],
  );
  const [selection, setSelection] = useState("");
  const [hovered, setHovered] = useState("");
  const [shown, setShown] = useState({ category, count: 60 });
  const visibleCount = shown.category === category ? shown.count : 60;
  const selected = nodes.some((node) => node.id === selection) ? selection : "";
  const featured =
    nodes.find((node) => node.id === hovered) ?? nodes.find((node) => node.id === selected);
  const relationships = useMemo(() => {
    const lookup = new Map(nodes.map((node) => [node.id, node]));
    const related = new Map<string, typeof nodes>();
    for (const edge of graph?.edges ?? []) {
      const source = lookup.get(edge.source);
      const target = lookup.get(edge.target);
      if (!source || !target) continue;
      related.set(source.id, [...(related.get(source.id) ?? []), target]);
      related.set(target.id, [...(related.get(target.id) ?? []), source]);
    }
    return related;
  }, [graph?.edges, nodes]);
  const neighbours = relationships.get(selected) ?? [];
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    startTransition(() => {
      edit({ key, value });
      router.push(`/discover/topology?${next}`, { scroll: false });
    });
  }
  return (
    <div className="page topology-page">
      <div className="page-intro">
        <div>
          <p className="eyebrow">Wardrobe topology</p>
          <h1>See the connections.</h1>
          <p>
            Explore pieces with related shapes, textures, and details. A fresh perspective on what
            you already own.
          </p>
        </div>
        <Link href="/search" className="button button-secondary">
          Find a piece
        </Link>
      </div>
      <div className="topology-toolbar">
        <label>
          Category
          <select value={category} onChange={(event) => update("category", event.target.value)}>
            <option value="">All pieces</option>
            {categories.map((item) => (
              <option key={item} value={item}>
                {categoryNames[item]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Colour by
          <select
            value={metric}
            disabled={listView}
            onChange={(event) => update("colour", event.target.value)}
          >
            <option value="category">Category</option>
            <option value="wear">Wear frequency</option>
          </select>
        </label>
        <div className="topology-view-controls" role="group" aria-label="Exploration view">
          <Button
            variant={listView ? "quiet" : "secondary"}
            aria-pressed={!listView}
            onClick={() => update("view", "")}
          >
            3D view
          </Button>
          <Button
            variant={listView ? "secondary" : "quiet"}
            aria-pressed={listView}
            onClick={() => update("view", "list")}
          >
            List view
          </Button>
        </div>
        <Button variant="quiet" disabled={query.isFetching} onClick={() => query.refetch()}>
          {query.isFetching ? "Updating…" : "Refresh map"}
        </Button>
      </div>
      {query.isPending ? (
        <LoadingState />
      ) : query.isError && !graph ? (
        <ErrorState error={query.error} retry={query.refetch} />
      ) : !nodes.length ? (
        <div className="empty-state">
          <Network size={48} strokeWidth={1} aria-hidden="true" />
          <h2>No reviewed pieces in this view yet.</h2>
          <p>
            {category
              ? "Choose another category to explore your wardrobe."
              : "Add a piece or finish photo review to bring your wardrobe into the map."}
          </p>
          <Link href={category ? "/discover/topology" : "/add"} className="button button-primary">
            {category ? "Show all categories" : "Add a piece"}
          </Link>
        </div>
      ) : (
        <>
          {query.isRefetchError && (
            <p className="processing-notice" role="alert">
              The map could not be refreshed. Use Refresh map to try again.
            </p>
          )}
          <p className="small" role="status">
            {nodes.length} pieces · {graph?.indexedCount} prepared for relationships
          </p>
          {graph?.embeddingAvailability === "UNAVAILABLE" ? (
            <p className="processing-notice" role="status">
              Relationships are temporarily unavailable. Every reviewed piece in this view is still
              available below.
            </p>
          ) : (
            graph &&
            graph.indexedCount < nodes.length && (
              <p className="processing-notice" role="status">
                Some pieces are still being prepared. They appear in the map and list; their
                relationships will appear when ready.
              </p>
            )
          )}
          {graph?.truncated && (
            <p className="processing-notice">
              This map shows {nodes.length} of {graph.eligibleCount} reviewed pieces. Filter by
              category to narrow it, or <Link href="/catalogue">browse the full catalogue</Link>.
            </p>
          )}
          {!listView && graph && (
            <section aria-label="Wardrobe map">
              <div className="topology-legend" aria-label="Map colour legend">
                {(metric === "category"
                  ? categories
                      .filter((item) => nodes.some((node) => node.category === item))
                      .map((item) => [categoryNames[item], categoryColours[item]])
                  : wearLabels.map((label, index) => [label, wearColours[index]])
                ).map(([label, colour]) => (
                  <span key={label}>
                    <i style={{ backgroundColor: colour }} aria-hidden="true" />
                    {label}
                  </span>
                ))}
              </div>
              <p id="topology-help" className="small">
                Drag to rotate, scroll or pinch to zoom, and drag with two fingers to pan. Click a
                piece to open it. You can also use the camera controls and list below.
              </p>
              <GraphBoundary key={category}>
                <GraphCanvas
                  graph={graph}
                  metric={metric}
                  showEdges={showEdges}
                  selected={selected}
                  onHover={setHovered}
                  onOpen={(id) => router.push(`/garments/${encodeURIComponent(id)}`)}
                />
              </GraphBoundary>
              <div className="topology-selection-controls">
                <label>
                  Highlight a piece
                  <select value={selected} onChange={(event) => setSelection(event.target.value)}>
                    <option value="">Choose a piece</option>
                    {nodes.map((node) => (
                      <option key={node.id} value={node.id}>
                        {node.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="topology-edge-toggle">
                  <input
                    type="checkbox"
                    checked={showEdges}
                    onChange={(event) => update("edges", event.target.checked ? "" : "hidden")}
                  />
                  Show relationships
                </label>
              </div>
              {featured && (
                <aside className="topology-featured" aria-label="Highlighted piece">
                  <div className="topology-featured-art">
                    <GarmentArt
                      garment={{
                        category: featured.category,
                        primaryColourHex: featured.colour,
                        assets: featured.assets,
                      }}
                    />
                  </div>
                  <div>
                    <h2>{featured.name}</h2>
                    <p>
                      {categoryNames[featured.category]} · {featured.wearCount} wears
                    </p>
                    <Link href={`/garments/${featured.id}`} className="button button-secondary">
                      Open piece
                    </Link>
                    {selected && !hovered && (
                      <p className="small">
                        {neighbours.length} related {neighbours.length === 1 ? "piece" : "pieces"}{" "}
                        highlighted
                      </p>
                    )}
                  </div>
                </aside>
              )}
              <p className="small">
                Connections reflect relative similarity within this view. They are suggestions to
                explore, rather than outfit recommendations.
              </p>
            </section>
          )}
          <section aria-label="Pieces in this map" className="topology-list">
            <h2>Pieces in this map</h2>
            <p className="small">
              Open any piece here. All map relationships are also listed by name.
            </p>
            <ul>
              {nodes.slice(0, visibleCount).map((node) => {
                const related = relationships.get(node.id) ?? [];
                return (
                  <li
                    key={node.id}
                    className={node.id === selected ? "topology-list-selected" : ""}
                  >
                    <Link href={`/garments/${node.id}`} className="topology-piece-link">
                      <div className="topology-piece-art">
                        <GarmentArt
                          garment={{
                            category: node.category,
                            primaryColourHex: node.colour,
                            assets: node.assets,
                          }}
                        />
                      </div>
                      <div>
                        <h3>{node.name}</h3>
                        <p>
                          {categoryNames[node.category]} · {node.wearCount} wears
                          {!node.indexed && " · Relationships pending"}
                        </p>
                      </div>
                    </Link>
                    {!listView && (
                      <Button
                        variant="quiet"
                        aria-label={`Highlight ${node.name}`}
                        aria-pressed={node.id === selected}
                        onClick={() => setSelection(node.id)}
                      >
                        Highlight
                      </Button>
                    )}
                    {related.length > 0 && (
                      <details>
                        <summary>
                          <ChevronRight size={16} aria-hidden="true" />
                          Related pieces ({related.length})
                        </summary>
                        <ul>
                          {related.map((item) => (
                            <li key={item.id}>
                              <Link href={`/garments/${item.id}`}>{item.name}</Link>
                            </li>
                          ))}
                        </ul>
                      </details>
                    )}
                  </li>
                );
              })}
            </ul>
            <p className="small" role="status">
              {Math.min(visibleCount, nodes.length)} of {nodes.length} pieces listed
            </p>
            {visibleCount < nodes.length && (
              <Button
                variant="secondary"
                onClick={() => setShown({ category, count: visibleCount + 60 })}
              >
                Show more pieces
              </Button>
            )}
          </section>
        </>
      )}
    </div>
  );
}
