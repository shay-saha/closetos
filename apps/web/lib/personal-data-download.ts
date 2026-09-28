import { apiResponse } from "@/lib/api";

export async function personalDataFile(signal: AbortSignal): Promise<Blob> {
  const response = await apiResponse("me/data", { signal });
  const file = await response.blob();
  const ending = (await file.slice(-128).text()).trimEnd();
  if (!ending.endsWith('"complete":true}'))
    throw new Error("Your data download was interrupted. Please try again.");
  return file;
}
