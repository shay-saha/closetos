"use client";

import { createContext, useContext, useState } from "react";

export type SearchPhoto = { id: string; name: string; dataUrl: string; base64: string };
const Photos = createContext<{
  photos: SearchPhoto[];
  add: (name: string, dataUrl: string) => string;
} | null>(null);

export function SearchPhotoProvider({ children }: { children: React.ReactNode }) {
  const [photos, setPhotos] = useState<SearchPhoto[]>([]);
  function add(name: string, dataUrl: string) {
    const id = crypto.randomUUID();
    const photo = { id, name, dataUrl, base64: dataUrl.slice(dataUrl.indexOf(",") + 1) };
    setPhotos((current) => [...current.slice(-2), photo]);
    return id;
  }
  return <Photos.Provider value={{ photos, add }}>{children}</Photos.Provider>;
}

export function useSearchPhotos() {
  const context = useContext(Photos);
  if (!context) throw new Error("Search photos require an authenticated wardrobe.");
  return context;
}

export async function readSearchPhoto(file: File) {
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type))
    throw new Error("Choose a JPEG, PNG, or WebP photograph.");
  if (file.size === 0 || file.size > 8 * 1024 * 1024)
    throw new Error("Choose a photograph up to 8 MB.");
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () =>
      reject(new Error("This photograph could not be read. Try selecting it again."));
    reader.onabort = () => reject(new Error("Photograph selection was cancelled."));
    reader.readAsDataURL(file);
  });
}
