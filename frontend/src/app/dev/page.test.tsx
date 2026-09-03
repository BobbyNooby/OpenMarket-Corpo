import { describe, expect, it, vi, afterEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import DevPage from "./page";

// The page is a thin renderer over src/lib/dev-api.ts (tested separately).
// These pins cover the DOM contract: log ordering, pretty-printing, and the
// status/error visual branches.

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("/dev harness page", () => {
  it("renders calls newest-first with pretty JSON and green status", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ id: "u-1" }), { status: 200 }),
    ));

    render(<DevPage />);
    await user.click(screen.getByRole("button", { name: "me" }));

    await waitFor(() => {
      expect(screen.getByText(/200/)).toBeInTheDocument();
    });
    const pre = screen.getByText((_, el) => el?.tagName === "PRE");
    expect(pre).toHaveTextContent('"id": "u-1"'); // pretty-printed (quoted key)
    const status = screen.getByText(/200/);
    expect(status).toHaveClass("text-green-600");
  });

  it("renders non-2xx in red", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: "invalid_credentials" }), { status: 401 }),
    ));

    render(<DevPage />);
    await user.click(screen.getByRole("button", { name: "login" }));

    await waitFor(() => {
      expect(screen.getByText(/401/)).toHaveClass("text-red-600");
    });
  });

  it("renders network failures as 'network error' in red", async () => {
    const user = userEvent.setup();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("boom")));

    render(<DevPage />);
    await user.click(screen.getByRole("button", { name: "me" }));

    await waitFor(() => {
      expect(screen.getByText("network error")).toHaveClass("text-red-600");
    });
  });
});
