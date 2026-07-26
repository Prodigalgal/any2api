import type { NextConfig } from "next";

const backendUrl = process.env.ANY2API_BACKEND_URL ?? "http://127.0.0.1:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      { source: "/v1/:path*", destination: `${backendUrl}/v1/:path*` },
      { source: "/api/:path*", destination: `${backendUrl}/api/:path*` },
      { source: "/actuator/:path*", destination: `${backendUrl}/actuator/:path*` }
    ];
  }
};

export default nextConfig;

