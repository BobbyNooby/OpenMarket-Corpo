import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The browser only ever talks to this origin — /api/* is proxied to the
  // gateway server-side so session cookies stay same-origin (SameSite=Lax
  // won't cross ports, and no CORS config exists anywhere). Production keeps
  // the same shape: frontend and gateway behind one host.
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: "http://localhost:3000/api/:path*",
      },
    ];
  },
};

export default nextConfig;
