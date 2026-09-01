#!/usr/bin/env python3
"""
Fake Discord API for the auth flow-test.

Implements just enough of https://discord.com/developers/docs to exercise
the authorization-code grant against the REAL auth service:

  POST /api/oauth2/token    -> access-token response (form-urlencoded in)
  GET  /api/users/@me       -> a Discord User object (real schema)

The fake user is themed and configurable via env (FAKE_USER_JSON).
Runs on port 5399.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs

FAKE_USER = {
    "id": "223749168869212160",
    "username": "garen",
    "discriminator": "0",
    "global_name": "Garen Crownguard",
    "avatar": "8342729096ea3675442027381ff50dfe",
    "verified": True,
    "email": "garen@demaciabook.com",
    "flags": 64,
    "premium_type": 0,
    "public_flags": 64,
}
FAKE_USER = json.loads(os.environ.get("FAKE_USER_JSON")) if os.environ.get("FAKE_USER_JSON") else FAKE_USER


class Handler(BaseHTTPRequestHandler):
    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path.startswith("/api/oauth2/token"):
            length = int(self.headers.get("Content-Length", 0))
            form = parse_qs(self.rfile.read(length).decode())
            if form.get("grant_type") != ["authorization_code"] or "code" not in form:
                self._json(400, {"error": "invalid_grant"})
                return
            print(f"[fake-discord] token exchange ok (code={form['code'][0]!r})", flush=True)
            self._json(200, {
                "access_token": "FAKE_DISCORD_ACCESS_TOKEN",
                "token_type": "Bearer",
                "expires_in": 604800,
                "refresh_token": "FAKE_REFRESH",
                "scope": "identify email",
            })
        else:
            self._json(404, {"message": "Unknown route", "code": 0})

    def do_GET(self):
        if self.path.startswith("/api/users/@me"):
            auth = self.headers.get("Authorization", "")
            if auth != "Bearer FAKE_DISCORD_ACCESS_TOKEN":
                self._json(401, {"message": "401: Unauthorized", "code": 0})
                return
            print("[fake-discord] served /users/@me", flush=True)
            self._json(200, FAKE_USER)
        else:
            self._json(404, {"message": "Unknown route", "code": 0})

    def log_message(self, *args):  # silence default request logging
        pass


if __name__ == "__main__":
    print("[fake-discord] listening on :5399", flush=True)
    HTTPServer(("127.0.0.1", 5399), Handler).serve_forever()
