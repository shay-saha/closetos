"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Shirt, LayoutGrid, Plus, LogOut, ListChecks, Settings, Layers } from "lucide-react";
import { signOut } from "next-auth/react";
import { Button } from "./ui/button";

const destinations = [
  { href: "/wardrobe", label: "Wardrobe", Icon: Shirt },
  { href: "/catalogue", label: "Catalogue", Icon: LayoutGrid },
  { href: "/review", label: "Review", Icon: ListChecks },
  { href: "/outfits", label: "Outfits", Icon: Layers },
];

export function ClosetShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  return (
    <div className="closet-shell">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="app-header">
        <Link href="/wardrobe" className="wordmark" aria-label="Closet OS home">
          CLOSET<span>{"//"}</span>OS
        </Link>
        <nav aria-label="Main navigation">
          {destinations.map(({ href, label, Icon }) => (
            <Link key={href} href={href} aria-current={path === href ? "page" : undefined}>
              <Icon size={18} />
              {label}
            </Link>
          ))}
        </nav>
        <div className="header-actions">
          <Link
            href="/settings"
            className="button button-quiet"
            aria-label="Settings"
            aria-current={path === "/settings" ? "page" : undefined}
          >
            <Settings size={18} />
          </Link>
          <Link href="/add" className="button button-primary">
            <Plus size={18} />
            <span>Add a piece</span>
          </Link>
          <Button
            variant="quiet"
            aria-label="Sign out"
            onClick={() => signOut({ callbackUrl: "/signin" })}
          >
            <LogOut size={18} />
          </Button>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        {children}
      </main>
      <footer className="app-footer">
        <span>A little more intention. A little less forgotten.</span>
        <span>Your wardrobe, considered.</span>
      </footer>
    </div>
  );
}
