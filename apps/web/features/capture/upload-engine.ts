import { api } from "@/lib/api";
import { uploadStore, type QueuedUpload } from "./upload-store";

type Reservation = {
  garmentId: string;
  imageId: string;
  upload: { url: string; method: string; headers: Record<string, string> };
};
type Processing = { state: string; canRetry: boolean; failureDetail?: string };

function put(
  reservation: Reservation,
  file: Blob,
  signal: AbortSignal,
  progress: (value: number) => void,
) {
  return new Promise<void>((resolve, reject) => {
    const request = new XMLHttpRequest();
    const abort = () => request.abort();
    signal.addEventListener("abort", abort, { once: true });
    request.open(reservation.upload.method, reservation.upload.url);
    for (const [name, value] of Object.entries(reservation.upload.headers))
      request.setRequestHeader(name, value);
    request.timeout = 120_000;
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) progress(Math.round((event.loaded / event.total) * 100));
    };
    request.onload = () =>
      request.status >= 200 && request.status < 300
        ? resolve()
        : reject(new Error("The photograph could not be uploaded. Please retry."));
    request.onerror = request.ontimeout = () =>
      reject(new Error("The upload was interrupted. Check your connection and retry."));
    request.onabort = () => reject(new DOMException("Upload paused", "AbortError"));
    request.onloadend = () => signal.removeEventListener("abort", abort);
    if (signal.aborted) reject(new DOMException("Upload paused", "AbortError"));
    else request.send(file);
  });
}

function pause(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(new DOMException("Upload paused", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, 2000);
    if (signal.aborted) abort();
    else signal.addEventListener("abort", abort, { once: true });
  });
}

export class UploadEngine {
  private items: QueuedUpload[] = [];
  private listeners = new Set<() => void>();
  private active = new Set<string>();
  private controller = new AbortController();
  private loaded = false;
  constructor(
    private wardrobeId: string,
    private changed: () => void,
  ) {}
  snapshot = () => this.items;
  isReady = () => this.loaded;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  private emit() {
    for (const listener of this.listeners) listener();
  }

  async start() {
    if (this.controller.signal.aborted) this.controller = new AbortController();
    const controller = this.controller;
    const items = await uploadStore.list(this.wardrobeId);
    if (controller.signal.aborted) return;
    this.items = items.map((item) => ({
      ...item,
      state: item.state === "uploading" ? "queued" : item.state,
    }));
    this.loaded = true;
    this.emit();
    this.pump();
  }

  stop() {
    this.controller.abort();
  }

  async add(files: File[]) {
    if (!this.loaded) throw new Error("Your upload queue is still loading. Try again shortly.");
    if (this.items.length + files.length > 50)
      throw new Error("Finish or dismiss some uploads before adding more than 50 photographs.");
    const types: Record<string, string> = {
      jpg: "image/jpeg",
      jpeg: "image/jpeg",
      png: "image/png",
      webp: "image/webp",
      heic: "image/heic",
      heif: "image/heif",
    };
    const prepared = [];
    for (const file of files) {
      const mimeType = file.type || types[file.name.split(".").pop()?.toLowerCase() ?? ""];
      if (!Object.values(types).includes(mimeType))
        throw new Error(`${file.name}: choose a JPEG, PNG, WebP or HEIC photograph.`);
      if (!file.size || file.size > 26_214_400)
        throw new Error(`${file.name}: photographs must be between 1 byte and 25 MB.`);
      const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", await file.arrayBuffer()));
      const id = crypto.randomUUID();
      const upload: QueuedUpload = {
        key: `${this.wardrobeId}:${id}`,
        wardrobeId: this.wardrobeId,
        id,
        filename: file.name,
        mimeType,
        size: file.size,
        checksumSha256: btoa(String.fromCharCode(...hash)),
        file,
        state: "queued",
        progress: 0,
      };
      prepared.push(upload);
    }
    for (const upload of prepared) {
      await uploadStore.save(upload);
      this.items = [...this.items, upload];
    }
    this.emit();
    this.pump();
  }

  async retry(id: string) {
    const item = this.items.find((upload) => upload.id === id);
    if (!item || item.state !== "failed") return;
    if (!item.file && item.garmentId && item.imageId) {
      const progress = await api<Processing>(`processing/${item.imageId}`, {
        signal: this.controller.signal,
      });
      if (progress.state === "FAILED")
        await api(`garments/${item.garmentId}/processing/retry`, {
          method: "POST",
          signal: this.controller.signal,
        });
      await this.update(id, { state: "processing", error: undefined });
    } else await this.update(id, { state: "queued", error: undefined, progress: 0 });
    this.pump();
  }

  async dismiss(id: string) {
    const item = this.items.find((upload) => upload.id === id);
    if (!item || this.active.has(id)) return;
    await uploadStore.remove(item.key);
    this.items = this.items.filter((upload) => upload.id !== id);
    this.emit();
  }

  private async update(id: string, patch: Partial<QueuedUpload>) {
    const item = this.items.find((upload) => upload.id === id);
    if (!item) return;
    const next = { ...item, ...patch };
    this.items = this.items.map((upload) => (upload.id === id ? next : upload));
    this.emit();
    await uploadStore.save(next);
  }

  private pump() {
    if (this.controller.signal.aborted || !this.loaded) return;
    for (const item of this.items) {
      if (this.active.size >= 2) break;
      if (this.active.has(item.id) || !["queued", "processing"].includes(item.state)) continue;
      this.active.add(item.id);
      void this.run(item).finally(() => {
        this.active.delete(item.id);
        this.pump();
      });
    }
  }

  private async run(item: QueuedUpload) {
    const signal = this.controller.signal;
    try {
      if (item.state === "queued") {
        if (!item.file) throw new Error("Choose this photograph again to upload it.");
        await this.update(item.id, { state: "uploading" });
        const reservation = await api<Reservation>("garments/uploads", {
          method: "POST",
          signal,
          headers: { "Idempotency-Key": item.id },
          body: JSON.stringify({
            filename: item.filename,
            mimeType: item.mimeType,
            size: item.size,
            checksumSha256: item.checksumSha256,
            imageRole: "FRONT",
          }),
        });
        await this.update(item.id, {
          garmentId: reservation.garmentId,
          imageId: reservation.imageId,
        });
        this.changed();
        await put(reservation, item.file, signal, (progress) => {
          this.items = this.items.map((upload) =>
            upload.id === item.id ? { ...upload, progress } : upload,
          );
          this.emit();
        });
        await this.update(item.id, { state: "processing", file: undefined, progress: 100 });
        item = { ...item, garmentId: reservation.garmentId, imageId: reservation.imageId };
      }
      if (!item.imageId) throw new Error("This upload has no photograph to process.");
      while (!signal.aborted) {
        const progress = await api<Processing>(`processing/${item.imageId}`, { signal });
        if (["READY", "READY_FOR_REVIEW"].includes(progress.state)) {
          await this.update(item.id, { state: "ready", file: undefined, error: undefined });
          this.changed();
          return;
        }
        if (progress.state === "FAILED") {
          await this.update(item.id, {
            state: "failed",
            canRetry: progress.canRetry,
            error: progress.failureDetail ?? "This photograph could not be processed.",
          });
          this.changed();
          return;
        }
        await pause(signal);
      }
    } catch (error) {
      if (signal.aborted) return;
      await this.update(item.id, {
        state: "failed",
        canRetry: true,
        error: error instanceof Error ? error.message : "This upload could not be completed.",
      }).catch(() => {
        // The failure is already visible in memory if browser storage is unavailable.
      });
    }
  }
}
