import { getServerSession } from "next-auth";
import { redirect } from "next/navigation";
import { authOptions } from "@/lib/auth";
import { ClosetShell } from "@/components/closet-shell";
import { CaptureProvider } from "@/features/capture/capture-provider";
import { SearchPhotoProvider } from "@/features/search/search-photos";
import { Providers } from "@/components/providers";

export default async function ClosetLayout({ children }: { children: React.ReactNode }) {
  const session = await getServerSession(authOptions);
  if (!session?.user.id) redirect("/signin");
  return (
    <Providers key={session.user.id}>
      <CaptureProvider accountId={session.user.id}>
        <SearchPhotoProvider>
          <ClosetShell>{children}</ClosetShell>
        </SearchPhotoProvider>
      </CaptureProvider>
    </Providers>
  );
}
