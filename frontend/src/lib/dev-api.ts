// The /dev harness's API layer, extracted so the request-shape contracts are
// testable without a browser: the fetcher is injected, every path is
// same-origin relative (cookies ride the Next.js rewrite to the gateway),
// and bodies are exact. The page renders; this module decides.

export type Call = { name: string; status: number | null; body: string };
export type CallSink = (call: Call) => void;
export type CallSpec = { name: string; path: string; init?: RequestInit };

/** The five auth harness calls — these ARE the documented request shapes. */
export function devCalls(
  email: string,
  password: string,
  name: string,
): Record<string, CallSpec> {
  const json = (body: unknown) => JSON.stringify(body);
  return {
    register: {
      name: "register",
      path: "/api/v1/auth/register",
      init: { method: "POST", body: json({ email, password, name }) },
    },
    login: {
      name: "login",
      path: "/api/v1/auth/login",
      init: { method: "POST", body: json({ email, password }) },
    },
    me: { name: "me", path: "/api/v1/users/me" },
    refresh: { name: "refresh", path: "/api/v1/auth/refresh", init: { method: "POST" } },
    logout: { name: "logout", path: "/api/v1/auth/logout", init: { method: "POST" } },
  };
}

/**
 * The messaging harness calls — mirrors contracts/openapi/messaging.v1.yaml.
 * Raw on purpose: an unparsed conversationId 400s at the gateway (uuid
 * validation), which IS the instructive result. Content is sent verbatim —
 * the server trims, so the raw harness can prove it.
 */
export function messagingCalls(
  otherUserId: string,
  listingId: string,
  conversationId: string,
  content: string,
): Record<string, CallSpec> {
  const json = (body: unknown) => JSON.stringify(body);
  const conv = `/api/v1/messaging/conversations/${conversationId}`;
  return {
    conversations: { name: "conversations", path: "/api/v1/messaging/conversations" },
    unread: { name: "unread", path: "/api/v1/messaging/conversations/unread-count" },
    createConversation: {
      name: "create conversation",
      path: "/api/v1/messaging/conversations",
      init: {
        method: "POST",
        body: json({
          otherUserId,
          listingId: listingId === "" ? null : listingId,
        }),
      },
    },
    messages: { name: "messages", path: `${conv}/messages` },
    send: {
      name: "send",
      path: `${conv}/messages`,
      init: { method: "POST", body: json({ content }) },
    },
    read: { name: "mark read", path: `${conv}/read`, init: { method: "POST" } },
  };
}

/** Pretty-print JSON bodies; empty or non-JSON text passes through raw. */
export function prettyBody(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** Executes one harness call against the given fetcher, logging the outcome. */
export async function call(
  sink: CallSink,
  fetcher: typeof fetch,
  spec: CallSpec,
): Promise<void> {
  try {
    const res = await fetcher(spec.path, {
      ...spec.init,
      headers: { "Content-Type": "application/json" },
    });
    const text = await res.text();
    sink({
      name: spec.name,
      status: res.status,
      body: prettyBody(text) || "(empty body)",
    });
  } catch (e) {
    sink({ name: spec.name, status: null, body: String(e) });
  }
}
