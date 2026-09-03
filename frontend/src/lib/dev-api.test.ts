import { describe, expect, it, vi } from "vitest";
import { call, devCalls, messagingCalls, prettyBody } from "./dev-api";

// The harness's request shapes ARE a contract: same-origin relative paths
// (cookies ride the rewrite), exact bodies, JSON content-type on everything.

describe("devCalls request shapes", () => {
  it("register posts the exact body to the same-origin path", () => {
    const spec = devCalls("garen@demaciabook.com", "pw", "Garen").register;
    expect(spec.path).toBe("/api/v1/auth/register");
    expect(spec.path.startsWith("/")).toBe(true); // same-origin, never absolute
    expect(JSON.parse(spec.init!.body as string)).toEqual({
      email: "garen@demaciabook.com",
      password: "pw",
      name: "Garen",
    });
  });

  it("login posts exactly email+password", () => {
    const spec = devCalls("a@b.c", "pw", "Name").login;
    expect(spec.path).toBe("/api/v1/auth/login");
    expect(JSON.parse(spec.init!.body as string)).toEqual({
      email: "a@b.c",
      password: "pw",
    });
  });

  it("me/refresh/logout carry no body — POSTs included", () => {
    const specs = devCalls("a@b.c", "pw", "n");
    expect(specs.me).toEqual({ name: "me", path: "/api/v1/users/me" });
    expect(specs.refresh).toEqual({
      name: "refresh",
      path: "/api/v1/auth/refresh",
      init: { method: "POST" },
    });
    expect(specs.logout).toEqual({
      name: "logout",
      path: "/api/v1/auth/logout",
      init: { method: "POST" },
    });
  });
});

describe("messagingCalls request shapes", () => {
  it("targets the same-origin messaging prefix", () => {
    const specs = messagingCalls(
      "11111111-1111-1111-1111-111111111111",
      "",
      "22222222-2222-2222-2222-222222222222",
      "hello",
    );
    for (const spec of Object.values(specs)) {
      expect(spec.path.startsWith("/api/v1/messaging")).toBe(true);
    }
  });

  it("create posts otherUserId with null listingId when none given", () => {
    const spec = messagingCalls(
      "11111111-1111-1111-1111-111111111111",
      "",
      "",
      "",
    ).createConversation;
    expect(spec.name).toBe("create conversation");
    expect(spec.path).toBe("/api/v1/messaging/conversations");
    expect(spec.init!.method).toBe("POST");
    expect(JSON.parse(spec.init!.body as string)).toEqual({
      otherUserId: "11111111-1111-1111-1111-111111111111",
      listingId: null,
    });
  });

  it("create forwards a filled-in listingId as a string", () => {
    const spec = messagingCalls(
      "11111111-1111-1111-1111-111111111111",
      "33333333-3333-3333-3333-333333333333",
      "",
      "",
    ).createConversation;
    expect(JSON.parse(spec.init!.body as string)).toEqual({
      otherUserId: "11111111-1111-1111-1111-111111111111",
      listingId: "33333333-3333-3333-3333-333333333333",
    });
  });

  it("send interpolates the conversation id and trims nothing (raw harness)", () => {
    const spec = messagingCalls(
      "x",
      "",
      "22222222-2222-2222-2222-222222222222",
      "  yo  ",
    ).send;
    expect(spec.path).toBe(
      "/api/v1/messaging/conversations/22222222-2222-2222-2222-222222222222/messages",
    );
    expect(spec.init!.method).toBe("POST");
    expect(JSON.parse(spec.init!.body as string)).toEqual({ content: "  yo  " });
  });

  it("read and messages address the conversation; conversations + unread need none", () => {
    const specs = messagingCalls(
      "x",
      "",
      "22222222-2222-2222-2222-222222222222",
      "y",
    );
    expect(specs.read.path).toBe(
      "/api/v1/messaging/conversations/22222222-2222-2222-2222-222222222222/read",
    );
    expect(specs.read.init!.method).toBe("POST");
    expect(specs.messages.path).toBe(
      "/api/v1/messaging/conversations/22222222-2222-2222-2222-222222222222/messages",
    );
    expect(specs.conversations).toEqual({
      name: "conversations",
      path: "/api/v1/messaging/conversations",
    });
    expect(specs.unread).toEqual({
      name: "unread",
      path: "/api/v1/messaging/conversations/unread-count",
    });
  });
});

describe("call()", () => {
  it("forces the JSON content-type header on every request", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(null, { status: 204 }),
    );
    const sink = vi.fn();
    await call(sink, fetcher, devCalls("a@b.c", "pw", "n").refresh);
    const [, init] = fetcher.mock.calls[0];
    expect(init.headers).toEqual({ "Content-Type": "application/json" });
  });

  it("logs newest-first via the sink with pretty JSON and (empty body) fallback", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    );
    const sink = vi.fn();
    await call(sink, fetcher, devCalls("a@b.c", "pw", "n").me);
    expect(sink).toHaveBeenCalledWith({
      name: "me",
      status: 200,
      body: '{\n  "ok": true\n}',
    });

    const empty = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    await call(sink, empty, devCalls("a@b.c", "pw", "n").logout);
    expect(sink).toHaveBeenLastCalledWith({
      name: "logout",
      status: 204,
      body: "(empty body)",
    });
  });

  it("logs network failures with a null status", async () => {
    const fetcher = vi.fn().mockRejectedValue(new TypeError("boom"));
    const sink = vi.fn();
    await call(sink, fetcher, devCalls("a@b.c", "pw", "n").me);
    expect(sink).toHaveBeenCalledWith({
      name: "me",
      status: null,
      body: "TypeError: boom",
    });
  });

  it("prettyBody passes non-JSON through raw", () => {
    expect(prettyBody("<html>gateway</html>")).toBe("<html>gateway</html>");
  });
});
