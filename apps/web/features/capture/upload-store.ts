export type UploadState = "queued" | "uploading" | "processing" | "ready" | "reviewed" | "failed";
export type QueuedUpload = {
  key: string;
  wardrobeId: string;
  id: string;
  filename: string;
  mimeType: string;
  size: number;
  checksumSha256: string;
  file?: Blob;
  garmentId?: string;
  imageId?: string;
  state: UploadState;
  progress: number;
  error?: string;
  canRetry?: boolean;
};

async function database() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("closetos-uploads", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("uploads", { keyPath: "key" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error("This browser could not save your upload queue."));
  });
}

async function transact<T>(
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest<T>,
) {
  const db = await database();
  try {
    return await new Promise<T>((resolve, reject) => {
      const transaction = db.transaction("uploads", mode);
      const request = operation(transaction.objectStore("uploads"));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onerror = transaction.onabort = () =>
        reject(new Error("Your upload queue could not be saved. Check available browser storage."));
    });
  } finally {
    db.close();
  }
}

export const uploadStore = {
  async list(wardrobeId: string): Promise<QueuedUpload[]> {
    const all = await transact("readonly", (store) => store.getAll());
    return (all as QueuedUpload[]).filter((upload) => upload.wardrobeId === wardrobeId);
  },
  async save(upload: QueuedUpload) {
    await transact("readwrite", (store) => store.put(upload));
  },
  async remove(key: string) {
    await transact("readwrite", (store) => store.delete(key));
  },
};
