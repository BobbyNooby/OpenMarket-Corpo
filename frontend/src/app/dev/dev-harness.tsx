"use client";

// Raw API harness: exercises the full chain — rewrite → gateway →
// gRPC edge introspection → services → Postgres — with nothing hidden.
// Deliberately unpolished: raw statuses and JSON bodies ARE the feature.
// Request shapes live in src/lib/dev-api.ts (tested there).
// Gated out of production builds by page.tsx (pinned there).
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  call,
  devCalls,
  messagingCalls,
  type Call,
  type CallSpec,
} from "@/lib/dev-api";

const inputClass = "border rounded px-2 py-1 text-sm bg-background";

export default function DevHarness() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [otherUserId, setOtherUserId] = useState("");
  const [listingId, setListingId] = useState("");
  const [conversationId, setConversationId] = useState("");
  const [content, setContent] = useState("");
  const [calls, setCalls] = useState<Call[]>([]);

  const run = (spec: CallSpec) =>
    call((c) => setCalls((prev) => [c, ...prev]), fetch, spec);

  const auth = devCalls(email, password, name);
  const msg = messagingCalls(otherUserId, listingId, conversationId, content);

  return (
    <main className="max-w-2xl mx-auto p-6 space-y-4 font-mono text-sm">
      <h1 className="text-lg font-bold">/dev — API harness</h1>
      <p className="text-muted-foreground">
        Everything below rides the same-origin rewrite to the gateway (:3000)
        → edge check → services. Cookies are handled by the browser; login
        first, then the messaging calls work against your session.
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
        <Button variant="outline" onClick={() => run(auth.register)}>
          register
        </Button>
        <Button variant="outline" onClick={() => run(auth.login)}>
          login
        </Button>
        <Button variant="outline" onClick={() => run(auth.me)}>
          me
        </Button>
        <Button variant="outline" onClick={() => run(auth.refresh)}>
          refresh
        </Button>
        <Button variant="outline" onClick={() => run(auth.logout)}>
          logout
        </Button>
      </div>

      <h2 className="text-sm font-bold pt-2">messaging</h2>
      <div className="flex flex-wrap gap-2 items-center">
        <input
          className={inputClass}
          value={otherUserId}
          onChange={(e) => setOtherUserId(e.target.value)}
          placeholder="otherUserId (uuid)"
        />
        <input
          className={inputClass}
          value={listingId}
          onChange={(e) => setListingId(e.target.value)}
          placeholder="listingId (optional uuid)"
        />
        <input
          className={inputClass}
          value={conversationId}
          onChange={(e) => setConversationId(e.target.value)}
          placeholder="conversationId (uuid)"
        />
        <input
          className={inputClass}
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder="message content"
        />
      </div>

      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => run(msg.conversations)}>
          conversations
        </Button>
        <Button variant="outline" onClick={() => run(msg.unread)}>
          unread
        </Button>
        <Button variant="outline" onClick={() => run(msg.createConversation)}>
          + conversation
        </Button>
        <Button variant="outline" onClick={() => run(msg.messages)}>
          messages
        </Button>
        <Button variant="outline" onClick={() => run(msg.send)}>
          send
        </Button>
        <Button variant="outline" onClick={() => run(msg.read)}>
          mark read
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
