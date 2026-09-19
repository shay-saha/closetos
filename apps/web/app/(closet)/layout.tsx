import { getServerSession } from "next-auth";
import { redirect } from "next/navigation";
import { authOptions } from "@/lib/auth";
import { ClosetShell } from "@/components/closet-shell";

export default async function ClosetLayout({ children }: { children: React.ReactNode }) {
  const session = await getServerSession(authOptions);
  if (!session) redirect("/signin");
  return <ClosetShell>{children}</ClosetShell>;
}
