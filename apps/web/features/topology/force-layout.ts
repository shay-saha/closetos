import {
  forceSimulation,
  forceLink,
  forceManyBody,
  forceCollide,
  forceCenter,
  forceX,
  forceY,
  forceZ,
  type SimulationNode,
} from "d3-force-3d";
import type { GraphPosition, LayoutInput } from "./types";

export function layoutGraph(input: LayoutInput): GraphPosition[] {
  if (input.nodes.length > 2000 || input.edges.length > 10_000)
    throw new Error("Graph layout exceeds its supported size.");
  if (input.nodes.length === 0) return [];
  const ids = new Set(input.nodes.map((node) => node.id));
  if (
    ids.size !== input.nodes.length ||
    input.edges.some(
      (edge) =>
        !ids.has(edge.source) ||
        !ids.has(edge.target) ||
        edge.source === edge.target ||
        !Number.isFinite(edge.weight) ||
        edge.weight < 0 ||
        edge.weight > 1,
    )
  )
    throw new Error("Graph relationships are invalid.");
  const nodes: SimulationNode[] = input.nodes.map((node) => ({ id: node.id }));
  const edges = input.edges.map((edge) => ({ ...edge }));
  const simulation = forceSimulation(nodes, 3)
    .stop()
    .force(
      "links",
      forceLink<SimulationNode>(edges)
        .id((node) => node.id)
        .distance((edge) => 5 + 12 * (1 - edge.weight))
        .strength((edge) => 0.2 + 0.6 * edge.weight),
    )
    .force("charge", forceManyBody().strength(-18))
    .force("collision", forceCollide(1.4))
    .force("centre", forceCenter(0, 0, 0))
    .force("x", forceX(0).strength(0.025))
    .force("y", forceY(0).strength(0.025))
    .force("z", forceZ(0).strength(0.025))
    .alphaDecay(0.04);
  simulation.tick(180);
  const positions = simulation.nodes().map((node) => ({
    id: node.id,
    x: node.x ?? 0,
    y: node.y ?? 0,
    z: node.z ?? 0,
  }));
  if (positions.some((node) => ![node.x, node.y, node.z].every(Number.isFinite)))
    throw new Error("Graph layout did not converge.");
  const radius = Math.max(1, ...positions.map((node) => Math.hypot(node.x, node.y, node.z)));
  const scale = 12 / radius;
  return positions.map((node) => ({
    id: node.id,
    x: node.x * scale,
    y: node.y * scale,
    z: node.z * scale,
  }));
}
