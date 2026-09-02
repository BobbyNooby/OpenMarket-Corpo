"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

import { Button } from "@/components/ui/button";

export default function Home() {
  const { resolvedTheme, setTheme } = useTheme();

  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <h1 className="text-4xl font-semibold tracking-tight">OpenMarket</h1>
      <p className="max-w-md text-muted-foreground">
        In-game item trading marketplace — listings, multi-item offers, chat and
        reputation. v2 frontend: Next.js talking only to the Go gateway.
      </p>
      <div className="flex items-center gap-2">
        <Button disabled>Listings (coming soon)</Button>
        <Button
          variant="outline"
          size="icon"
          aria-label="Toggle theme"
          onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}
        >
          <Sun className="hidden dark:block" />
          <Moon className="block dark:hidden" />
        </Button>
      </div>
    </main>
  );
}
