"use client";

// Raw API harness: exercises the full chain — rewrite → gateway →
// gRPC edge introspection → auth → Postgres — with nothing hidden.
// Deliberately unpolished: raw statuses and JSON bodies ARE the feature.
// Request shapes live in src/lib/dev-api.ts (tested there).
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { call, devCalls, type Call, type CallSpec } from "@/lib/dev-api";

const inputClass = "border rounded px-2 py-1 text-sm bg-background";

export default function DevPage() {
  const [email, setEmail] = useState("garen@demaciabook.com");
  const [password, setPassword] = useState("Demacia4Ever22");
  const [name, setName] = useState("Garen Crownguard");
  const [calls, setCalls] = useState<Call[]>([]);

  const run = (spec: CallSpec) =>
    call((c) => setCalls((prev) => [c, ...prev]), fetch, spec);

  return (
    <main className="max-w-2xl mx-auto p-6 space-y-4 font-mono text-sm">
      <h1 className="text-lg font-bold">/dev — API harness</h1>
      <p className="text-muted-foreground">
        Everything below rides the same-origin rewrite to the gateway (:3000)
        → auth (:8080 REST + :9090 gRPC edge check). Cookies are handled by
        the browser.
      </p>

      <div className="flex flex-wrap gap-2 items-center">
        <input
          className={inputClass}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="email"
        />
        <input
          className={inputClass}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="password"
        />
        <input
          className={inputClass}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="name"
        />
      </div>

      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          onClick={() => run(devCalls(email, password, name).register)}
        >
          register
        </Button>
        <Button
          variant="outline"
          onClick={() => run(devCalls(email, password, name).login)}
        >
          login
        </Button>
        <Button variant="outline" onClick={() => run(devCalls(email, password, name).me)}>
          me
        </Button>
        <Button variant="outline" onClick={() => run(devCalls(email, password, name).refresh)}>
          refresh
        </Button>
        <Button variant="outline" onClick={() => run(devCalls(email, password, name).logout)}>
          logout
        </Button>
      </div>

      {calls.map((c, i) => (
        <section key={i} className="space-y-1">
          <div className="font-bold">
            {c.name} —{" "}
            <span
              className={
                c.status !== null && c.status < 400
                  ? "text-green-600"
                  : "text-red-600"
              }
            >
              {c.status ?? "network error"}
            </span>
          </div>
          <pre className="border rounded p-2 bg-muted whitespace-pre-wrap break-all">
            {c.body}
          </pre>
        </section>
      ))}
    </main>
  );
}
