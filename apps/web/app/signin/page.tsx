"use client";

import { signIn } from "next-auth/react";
import { useState } from "react";
import { ArrowUpRight } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function SignIn() {
  const [pending, setPending] = useState(false);
  return (
    <main className="signin">
      <p className="wordmark">
        CLOSET<span>{"//"}</span>OS
      </p>
      <div className="signin-copy">
        <p className="eyebrow">A wardrobe with possibility</p>
        <h1>
          You already own
          <br />
          <em>something wonderful.</em>
        </h1>
        <p>See it all. Rediscover your favourites. Make more of what you have.</p>
        <Button
          disabled={pending}
          onClick={async () => {
            setPending(true);
            await signIn("wardrobe", { callbackUrl: "/wardrobe" });
          }}
        >
          {pending ? "Opening your account…" : "Open your wardrobe"}
          <ArrowUpRight size={20} />
        </Button>
        <p className="small">Sign in or create an account to begin.</p>
      </div>
      <div className="signin-art" aria-hidden="true">
        <div className="art-rail" />
        <span className="art-piece piece-one" />
        <span className="art-piece piece-two" />
        <span className="art-piece piece-three" />
      </div>
      <footer>Your wardrobe. Your way.</footer>
    </main>
  );
}
