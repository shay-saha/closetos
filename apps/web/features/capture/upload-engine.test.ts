// @vitest-environment node
import "fake-indexeddb/auto";
import { File } from "node:buffer";
import { webcrypto, randomUUID } from "node:crypto";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { UploadEngine } from "./upload-engine";
import { uploadStore, type QueuedUpload } from "./upload-store";
import { api, ApiError } from "@/lib/api";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const apiMock = vi.mocked(api);
const engines: UploadEngine[] = [];

beforeEach(() => {
  vi.stubGlobal("crypto", { subtle: webcrypto.subtle, randomUUID });
  apiMock.mockReset();
});
afterEach(() => {
  for (const engine of engines) engine.stop();
  engines.length = 0;
  vi.unstubAllGlobals();
});

function engine(owner: string) {
  const instance = new UploadEngine(owner, vi.fn());
  engines.push(instance);
  return instance;
}
function stored(owner: string, patch: Partial<QueuedUpload> = {}): QueuedUpload {
  const id = randomUUID();
  return {
    id,
    key: `${owner}:${id}`,
    wardrobeId: owner,
    filename: "piece.png",
    mimeType: "image/png",
    size: 4,
    checksumSha256: "test-checksum",
    state: "uploading",
    progress: 45,
    file: new Blob(["test"]),
    ...patch,
  };
}

function uploadResponse(status: number) {
  const requests: {
    open: ReturnType<typeof vi.fn>;
    setRequestHeader: ReturnType<typeof vi.fn>;
    send: ReturnType<typeof vi.fn>;
  }[] = [];
  vi.stubGlobal(
    "XMLHttpRequest",
    class {
      status = status;
      timeout = 0;
      upload = {};
      open = vi.fn();
      setRequestHeader = vi.fn();
      onload = () => {};
      onloadend = () => {};
      onabort = () => {};
      send = vi.fn(() => {
        queueMicrotask(() => {
          this.onload();
          this.onloadend();
        });
      });
      abort() {
        this.onabort();
        this.onloadend();
      }
      constructor() {
        requests.push(this);
      }
    },
  );
  return requests;
}

function reservation() {
  return {
    garmentId: randomUUID(),
    imageId: randomUUID(),
    upload: {
      method: "PUT",
      url: "https://storage.example.test/signed-photo",
      headers: { "Content-Type": "image/png", "If-None-Match": "*" },
    },
  };
}

it.each([200, 412])(
  "confirms a conditional upload through owned processing after HTTP %i",
  async (status) => {
    const owner = randomUUID();
    const item = stored(owner);
    const reserved = reservation();
    await uploadStore.save(item);
    const requests = uploadResponse(status);
    apiMock.mockResolvedValueOnce(reserved).mockResolvedValueOnce({ state: "READY_FOR_REVIEW" });
    const instance = engine(owner);
    await instance.start();
    await vi.waitFor(() => expect(instance.snapshot()[0].state).toBe("ready"));
    expect(requests).toHaveLength(1);
    expect(requests[0].open).toHaveBeenCalledWith("PUT", reserved.upload.url);
    expect(requests[0].setRequestHeader).toHaveBeenCalledWith("If-None-Match", "*");
    expect(requests[0].send.mock.calls[0][0]).toHaveProperty("size", 4);
    expect(apiMock).toHaveBeenLastCalledWith(`processing/${reserved.imageId}`, expect.anything());
    expect((await uploadStore.list(owner))[0].file).toBeUndefined();
  },
);

it("retains the local photograph while an existing object awaits processing admission", async () => {
  const owner = randomUUID();
  const item = stored(owner);
  const reserved = reservation();
  await uploadStore.save(item);
  uploadResponse(412);
  apiMock.mockResolvedValueOnce(reserved).mockResolvedValue({ state: "AWAITING_UPLOAD" });
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(apiMock).toHaveBeenCalledTimes(2));
  expect(instance.snapshot()[0].state).toBe("processing");
  expect((await uploadStore.list(owner))[0].file?.size).toBe(4);
});

it("keeps the photograph and reports failure when the existing image was removed", async () => {
  const owner = randomUUID();
  const item = stored(owner);
  await uploadStore.save(item);
  uploadResponse(412);
  apiMock
    .mockResolvedValueOnce(reservation())
    .mockRejectedValueOnce(new ApiError(404, "Image not found."));
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(instance.snapshot()[0].state).toBe("failed"));
  expect(instance.snapshot()[0].error).toBe("Image not found.");
  expect((await uploadStore.list(owner))[0].file?.size).toBe(4);
});

it.each([403, 409, 500])(
  "retains the file and does not poll processing after HTTP %i",
  async (status) => {
    const owner = randomUUID();
    await uploadStore.save(stored(owner));
    uploadResponse(status);
    apiMock.mockResolvedValueOnce(reservation());
    const instance = engine(owner);
    await instance.start();
    await vi.waitFor(() => expect(instance.snapshot()[0].state).toBe("failed"));
    expect(apiMock).toHaveBeenCalledOnce();
    expect((await uploadStore.list(owner))[0].file?.size).toBe(4);
  },
);

it("does not treat an unexpected precondition failure as a completed upload", async () => {
  const owner = randomUUID();
  await uploadStore.save(stored(owner));
  uploadResponse(412);
  apiMock.mockResolvedValueOnce({
    ...reservation(),
    upload: { method: "PUT", url: "https://storage.example.test/photo", headers: {} },
  });
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(instance.snapshot()[0].state).toBe("failed"));
  expect(apiMock).toHaveBeenCalledOnce();
  expect((await uploadStore.list(owner))[0].file?.size).toBe(4);
});

it("recovers interrupted uploads under the same idempotency key and isolates owners", async () => {
  const owner = randomUUID();
  const item = stored(owner);
  const stranger = stored(randomUUID());
  await uploadStore.save(item);
  await uploadStore.save(stranger);
  apiMock.mockImplementation(() => new Promise(() => {}));
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(apiMock).toHaveBeenCalledOnce());
  expect(instance.snapshot()).toHaveLength(1);
  expect(apiMock.mock.calls[0][1]?.headers).toEqual({ "Idempotency-Key": item.id });
  expect(instance.snapshot()[0].file?.size).toBe(4);
  expect((await uploadStore.list(stranger.wardrobeId))[0].id).toBe(stranger.id);
});

it("limits active uploads to two and leaves queued files durable", async () => {
  const owner = randomUUID();
  for (let i = 0; i < 4; i++) await uploadStore.save(stored(owner, { state: "queued" }));
  apiMock.mockImplementation(() => new Promise(() => {}));
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(apiMock).toHaveBeenCalledTimes(2));
  expect(instance.snapshot().filter((item) => item.state === "queued")).toHaveLength(2);
  expect(await uploadStore.list(owner)).toHaveLength(4);
});

it("resumes processing without uploading the original again", async () => {
  const owner = randomUUID();
  const item = stored(owner, {
    state: "processing",
    file: undefined,
    imageId: randomUUID(),
    garmentId: randomUUID(),
    progress: 100,
  });
  await uploadStore.save(item);
  apiMock.mockResolvedValue({ state: "READY_FOR_REVIEW", canRetry: false });
  const instance = engine(owner);
  await instance.start();
  await vi.waitFor(() => expect(instance.snapshot()[0].state).toBe("ready"));
  expect(apiMock).toHaveBeenCalledWith(`processing/${item.imageId}`, expect.anything());
  expect(apiMock).toHaveBeenCalledOnce();
  expect((await uploadStore.list(owner))[0].file).toBeUndefined();
});

it("rejects unsupported and oversized files before creating a server draft", async () => {
  const instance = engine(randomUUID());
  await instance.start();
  await expect(
    instance.add([
      new File(["pdf"], "document.pdf", { type: "application/pdf" }) as unknown as globalThis.File,
    ]),
  ).rejects.toThrow("JPEG");
  await expect(
    instance.add([
      new File([new Uint8Array(26_214_401)], "large.png", {
        type: "image/png",
      }) as unknown as globalThis.File,
    ]),
  ).rejects.toThrow("25 MB");
  expect(apiMock).not.toHaveBeenCalled();
  expect(instance.snapshot()).toEqual([]);
});

it("does not start requests after disposal and survives an effect restart", async () => {
  const instance = engine(randomUUID());
  const first = instance.start();
  instance.stop();
  await first;
  expect(apiMock).not.toHaveBeenCalled();
  await instance.start();
  apiMock.mockImplementation(() => new Promise(() => {}));
  await instance.add([
    new File(["photo"], "photo.png", { type: "image/png" }) as unknown as globalThis.File,
  ]);
  await vi.waitFor(() => expect(apiMock).toHaveBeenCalledOnce());
  expect(JSON.parse(apiMock.mock.calls[0][1]?.body as string).checksumSha256).toHaveLength(44);
});
