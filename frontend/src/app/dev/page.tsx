import { notFound } from "next/navigation";
import DevHarness from "./dev-harness";

// Dev-only API harness: 404 in production builds so the raw introspection
// surface (and any demo credentials) never ship. Next inlines NODE_ENV at
// build time, so the predicate is pure and pinned in page.test.tsx.
export function isProductionBuild(nodeEnv: string | undefined): boolean {
  return nodeEnv === "production";
}

export default function DevPage() {
  if (isProductionBuild(process.env.NODE_ENV)) notFound();
  return <DevHarness />;
}
