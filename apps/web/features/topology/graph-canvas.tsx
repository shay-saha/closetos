"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Canvas, useThree, type ThreeEvent } from "@react-three/fiber";
import { Color, Object3D, type InstancedMesh } from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { Button } from "@/components/ui/button";
import { categoryNames } from "@/features/garments/types";
import { GarmentArt } from "@/features/garments/garment-art";
import { useGraphLayout } from "./use-graph-layout";
import { nodeColour, type ColourMetric, type GraphPosition, type WardrobeGraph } from "./types";

type Command = { kind: string; sequence: number };
type Props = {
  graph: WardrobeGraph;
  metric: ColourMetric;
  showEdges: boolean;
  selected: string;
  onHover: (id: string) => void;
  onOpen: (id: string) => void;
};

function Controls({
  command,
  positions,
  selected,
  onFailure,
}: {
  command: Command;
  positions: GraphPosition[];
  selected: string;
  onFailure: () => void;
}) {
  const { camera, gl, invalidate, size } = useThree();
  const controls = useRef<OrbitControls>(null);
  const lastCommand = useRef(-1);
  useEffect(() => {
    const orbit = new OrbitControls(camera, gl.domElement);
    orbit.enableDamping = false;
    orbit.minDistance = 3;
    orbit.maxDistance = 130;
    const changed = () => invalidate();
    orbit.addEventListener("change", changed);
    const lost = (event: Event) => {
      event.preventDefault();
      onFailure();
    };
    gl.domElement.addEventListener("webglcontextlost", lost);
    controls.current = orbit;
    return () => {
      orbit.removeEventListener("change", changed);
      orbit.dispose();
      gl.domElement.removeEventListener("webglcontextlost", lost);
    };
  }, [camera, gl, invalidate, onFailure]);
  useEffect(() => {
    const orbit = controls.current;
    if (!orbit) return;
    orbit.target.set(0, 0, 0);
    camera.position.set(0, 0, 34 / Math.min(1, size.width / size.height));
    orbit.update();
    invalidate();
  }, [camera, invalidate, positions, size.width, size.height]);
  useEffect(() => {
    const orbit = controls.current;
    if (!orbit || lastCommand.current === command.sequence) return;
    lastCommand.current = command.sequence;
    switch (command.kind) {
      case "in":
        orbit.dollyIn(0.8);
        break;
      case "out":
        orbit.dollyOut(0.8);
        break;
      case "left":
        orbit.rotateLeft(0.35);
        break;
      case "right":
        orbit.rotateLeft(-0.35);
        break;
      case "pan-left":
        orbit.pan(60, 0);
        break;
      case "pan-right":
        orbit.pan(-60, 0);
        break;
      case "focus": {
        const node = positions.find((position) => position.id === selected);
        if (node) {
          orbit.target.set(node.x, node.y, node.z);
          camera.position.set(node.x, node.y, node.z + 6);
        }
        break;
      }
      default:
        orbit.target.set(0, 0, 0);
        camera.position.set(0, 0, 34 / Math.min(1, size.width / size.height));
    }
    orbit.update();
    invalidate();
  }, [command, camera, invalidate, positions, selected, size.width, size.height]);
  return null;
}

function GraphScene({
  graph,
  positions,
  metric,
  showEdges,
  selected,
  onHover,
  onOpen,
}: Props & {
  positions: GraphPosition[];
}) {
  const mesh = useRef<InstancedMesh>(null);
  const { invalidate } = useThree();
  const points = useMemo(() => new Map(positions.map((node) => [node.id, node])), [positions]);
  const neighbours = useMemo(
    () =>
      new Set([
        selected,
        ...graph.edges
          .filter((edge) => edge.source === selected || edge.target === selected)
          .flatMap((edge) => [edge.source, edge.target]),
      ]),
    [graph.edges, selected],
  );
  const radius = Math.max(0.16, Math.min(0.7, 1.8 / Math.cbrt(graph.nodes.length)));
  useEffect(() => {
    if (!mesh.current) return;
    const object = new Object3D();
    const colour = new Color();
    graph.nodes.forEach((node, index) => {
      const point = points.get(node.id);
      if (!point) return;
      object.position.set(point.x, point.y, point.z);
      object.scale.setScalar(radius * (node.id === selected ? 1.4 : 1));
      object.updateMatrix();
      mesh.current!.setMatrixAt(index, object.matrix);
      colour.set(nodeColour(node, metric));
      if (selected && !neighbours.has(node.id)) colour.lerp(new Color("#f6f3ed"), 0.65);
      mesh.current!.setColorAt(index, colour);
    });
    mesh.current.instanceMatrix.needsUpdate = true;
    if (mesh.current.instanceColor) mesh.current.instanceColor.needsUpdate = true;
    mesh.current.computeBoundingSphere();
    invalidate();
  }, [graph.nodes, metric, neighbours, points, radius, selected, invalidate]);
  const lines = useMemo(() => {
    const coordinates: number[] = [];
    const colours: number[] = [];
    for (const edge of graph.edges) {
      const source = points.get(edge.source);
      const target = points.get(edge.target);
      if (!source || !target) continue;
      coordinates.push(source.x, source.y, source.z, target.x, target.y, target.z);
      const highlighted = selected && (edge.source === selected || edge.target === selected);
      const colour = new Color(highlighted ? "#953f2b" : selected ? "#e1dfd7" : "#626459");
      colours.push(colour.r, colour.g, colour.b, colour.r, colour.g, colour.b);
    }
    return { coordinates: new Float32Array(coordinates), colours: new Float32Array(colours) };
  }, [graph.edges, points, selected]);
  function nodeAt(event: ThreeEvent<PointerEvent | MouseEvent>) {
    return event.instanceId === undefined ? undefined : graph.nodes[event.instanceId];
  }
  return (
    <>
      {showEdges && (
        <lineSegments>
          <bufferGeometry>
            <bufferAttribute attach="attributes-position" args={[lines.coordinates, 3]} />
            <bufferAttribute attach="attributes-color" args={[lines.colours, 3]} />
          </bufferGeometry>
          <lineBasicMaterial vertexColors transparent opacity={0.8} toneMapped={false} />
        </lineSegments>
      )}
      <instancedMesh
        ref={mesh}
        args={[undefined, undefined, graph.nodes.length]}
        onPointerMove={(event) => {
          const node = nodeAt(event);
          if (node) {
            event.stopPropagation();
            onHover(node.id);
          }
        }}
        onPointerOut={() => {
          onHover("");
        }}
        onClick={(event) => {
          const node = nodeAt(event);
          if (node && event.delta < 6) {
            event.stopPropagation();
            onOpen(node.id);
          }
        }}
      >
        <sphereGeometry args={[1, 20, 12]} />
        <meshLambertMaterial />
      </instancedMesh>
    </>
  );
}

export default function GraphCanvas(props: Props) {
  const layout = useGraphLayout(props.graph);
  const [command, setCommand] = useState<Command>({ kind: "reset", sequence: 0 });
  const [failed, setFailed] = useState(false);
  const onFailure = useCallback(() => setFailed(true), []);
  const [hovered, setHovered] = useState("");
  const hoveredNode = props.graph.nodes.find((node) => node.id === hovered);
  function send(kind: string) {
    setCommand((previous) => ({ kind, sequence: previous.sequence + 1 }));
  }
  if (failed || layout?.error)
    return (
      <p className="processing-notice" role="status">
        {layout?.error ?? "The 3D view is unavailable here. Browse the pieces below."}
      </p>
    );
  if (!layout?.positions)
    return (
      <div className="topology-stage topology-loading" role="status">
        Arranging your wardrobe…
      </div>
    );
  return (
    <>
      <div
        className={`topology-stage${hovered ? " topology-hovering" : ""}`}
        role="group"
        aria-label="Interactive wardrobe graph"
        aria-describedby="topology-help"
      >
        <Canvas
          frameloop="demand"
          dpr={[1, 1.5]}
          camera={{ position: [0, 0, 38], fov: 45, near: 0.1, far: 300 }}
          gl={{ antialias: true, alpha: true }}
          fallback={<p>The 3D view is unavailable here. Browse the pieces below.</p>}
        >
          <ambientLight intensity={1.6} />
          <directionalLight position={[10, 15, 20]} intensity={2} />
          <Controls
            command={command}
            positions={layout.positions}
            selected={props.selected}
            onFailure={onFailure}
          />
          <GraphScene
            {...props}
            selected={hovered || props.selected}
            positions={layout.positions}
            onHover={(id) => {
              setHovered(id);
              props.onHover(id);
            }}
          />
        </Canvas>
        {hoveredNode && (
          <div className="topology-hover" role="tooltip">
            <div className="topology-hover-art">
              <GarmentArt
                garment={{
                  category: hoveredNode.category,
                  primaryColourHex: hoveredNode.colour,
                  assets: hoveredNode.assets,
                }}
              />
            </div>
            <div>
              <strong>{hoveredNode.name}</strong>
              <span>
                {categoryNames[hoveredNode.category]} · {hoveredNode.wearCount} wears
              </span>
            </div>
          </div>
        )}
      </div>
      <div className="topology-camera-controls" aria-label="Graph camera controls">
        {[
          ["in", "Zoom in"],
          ["out", "Zoom out"],
          ["left", "Rotate left"],
          ["right", "Rotate right"],
          ["pan-left", "Pan left"],
          ["pan-right", "Pan right"],
          ["reset", "Reset view"],
        ].map(([kind, label]) => (
          <Button key={kind} variant="quiet" onClick={() => send(kind)}>
            {label}
          </Button>
        ))}
        <Button variant="secondary" disabled={!props.selected} onClick={() => send("focus")}>
          Focus selected piece
        </Button>
      </div>
    </>
  );
}
