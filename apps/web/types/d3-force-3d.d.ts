declare module "d3-force-3d" {
  export interface SimulationNode {
    id: string;
    x?: number;
    y?: number;
    z?: number;
    vx?: number;
    vy?: number;
    vz?: number;
  }
  export interface SimulationLink<N extends SimulationNode> {
    source: string | N;
    target: string | N;
    weight: number;
  }
  interface Force {
    (alpha: number): void;
  }
  interface StrengthForce extends Force {
    strength(value: number): this;
  }
  interface LinkForce<N extends SimulationNode> extends Force {
    id(value: (node: N) => string): this;
    distance(value: (link: SimulationLink<N>) => number): this;
    strength(value: (link: SimulationLink<N>) => number): this;
  }
  interface Simulation<N extends SimulationNode> {
    stop(): this;
    force(name: string, force: Force): this;
    alphaDecay(value: number): this;
    tick(iterations: number): this;
    nodes(): N[];
  }
  export function forceSimulation<N extends SimulationNode>(
    nodes: N[],
    dimensions: number,
  ): Simulation<N>;
  export function forceLink<N extends SimulationNode>(links: SimulationLink<N>[]): LinkForce<N>;
  export function forceManyBody(): StrengthForce;
  export function forceCollide(radius: number): Force;
  export function forceCenter(x: number, y: number, z: number): Force;
  export function forceX(x: number): StrengthForce;
  export function forceY(y: number): StrengthForce;
  export function forceZ(z: number): StrengthForce;
}
