"use client";

import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { UploadEngine } from "./upload-engine";
import type { QueuedUpload } from "./upload-store";

const CaptureContext = createContext<UploadEngine | null>(null);
const empty: QueuedUpload[] = [];
const noSubscribe = () => () => {};
const emptySnapshot = () => empty;

export function CaptureProvider({
  accountId,
  children,
}: {
  accountId: string;
  children: React.ReactNode;
}) {
  const client = useQueryClient();
  const [storageError, setStorageError] = useState<string>();
  const wardrobe = useQuery({
    queryKey: ["capture-wardrobe", accountId],
    queryFn: () => api<{ id: string }>("wardrobes/current"),
  });
  const wardrobeId = wardrobe.data?.id;
  const engine = useMemo(
    () =>
      wardrobeId
        ? new UploadEngine(wardrobeId, () => {
            void client.invalidateQueries({ queryKey: ["garments"] });
            void client.invalidateQueries({ queryKey: ["search"] });
            void client.invalidateQueries({ queryKey: ["garment"] });
          })
        : null,
    [client, wardrobeId],
  );
  useEffect(() => {
    if (!engine) return;
    let disposed = false;
    void engine.start().catch((error: Error) => {
      if (!disposed) setStorageError(error.message);
    });
    return () => {
      disposed = true;
      engine.stop();
    };
  }, [engine]);
  return (
    <CaptureContext.Provider value={engine}>
      {storageError && (
        <p className="processing-notice" role="alert">
          {storageError}
        </p>
      )}
      {children}
    </CaptureContext.Provider>
  );
}

export function useCapture() {
  const engine = useContext(CaptureContext);
  const items = useSyncExternalStore(
    engine?.subscribe ?? noSubscribe,
    engine?.snapshot ?? emptySnapshot,
    emptySnapshot,
  );
  return { engine, items };
}
