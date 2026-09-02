"use client";

// Raw API harness: exercises the full chain — rewrite → gateway →
// gRPC edge introspection → auth → Postgres — with nothing hidden.
// Deliberately unpolished: raw statuses and JSON bodies ARE the feature.
import { useState } from "react";
import { Button } from "@/components/ui/button";

type Call = { name: string; status: number | null; body: string };

const inputClass = "border rounded px-2 py-1 text-sm bg-background";

export default function DevPage() {
  const [email, setEmail] = useState("garen@demaciabook.com");
  const [password, setPassword] = useState("Demacia4Ever22");
  const [name, setName] = useState("Garen Crownguard");
  const [calls, setCalls] = useState<Call[]>([]);

  async function call(name: string, path: string, init?: RequestInit) {
    try {
      const res = await fetch(path, {
        ...init,
        headers: { "Content-Type": "application/json" },
      });
      const text = await res.text();
      let pretty = text;
      try {
        pretty = JSON.stringify(JSON.parse(text), null, 2);
      } catch {
        // empty or non-JSON body — show it raw
      }
      setCalls((prev) => [
        { name, status: res.status, body: pretty || "(empty body)" },
        ...prev,
      ]);
    } catch (e) {
      setCalls((prev) => [
        { name, status: null, body: String(e) },
        ...prev,
      ]);
    }
  }

  const json = (body: unknown) => JSON.stringify(body);

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
          onClick={() =>
            call(
              "register",
              "/api/v1/auth/register",
              { method: "POST", body: json({ email, password, name }) },
            )
          }
        >
          register
        </Button>
        <Button
          variant="outline"
          onClick={() =>
            call(
              "login",
              "/api/v1/auth/login",
              { method: "POST", body: json({ email, password }) },
            )
          }
        >
          login
        </Button>
        <Button
          variant="outline"
          onClick={() => call("me", "/api/v1/users/me")}
        >
          me
        </Button>
        <Button
          variant="outline"
          onClick={() =>
            call("refresh", "/api/v1/auth/refresh", { method: "POST" })
          }
        >
          refresh
        </Button>
        <Button
          variant="outline"
          onClick={() =>
            call("logout", "/api/v1/auth/logout", { method: "POST" })
          }
        >
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
