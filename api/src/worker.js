import { Container, getRandom } from "@cloudflare/containers";

export class FluxzeroCliApi extends Container {
  defaultPort = 8080;
  sleepAfter = "10m";

  onStart() {
    console.log("Fluxzero CLI API container starting up");
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // CORS preflight
    if (request.method === 'OPTIONS' && url.pathname.startsWith('/api/')) {
      return new Response(null, {
        status: 200,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        },
      });
    }

    if (url.pathname === "/health") {
      return new Response("OK", {
        status: 200,
        headers: { "Content-Type": "text/plain" },
      });
    }

    // Route to container
    const container = await getRandom(env.FLUXZERO_CLI_API, 3);

    // Important: disable CF transforms; let us stream pass-through
    const upstream = await container.fetch(request, {
      cf: {
        cacheEverything: false,
        cacheTtl: 0,
        brotli: "off",
        polish: "off",
        minify: { javascript: false, css: false, html: false },
      },
    });

    // For /api/* add CORS and sanitize headers; otherwise return as-is
    if (url.pathname.startsWith('/api/')) {
      const headers = new Headers(upstream.headers);

      // Strip hop-by-hop headers that must not be forwarded
      const hopByHop = [
        "connection",
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "upgrade",
        "te",
        "trailer",
      ];
      for (const h of hopByHop) headers.delete(h);

      // For ZIP (or anything) never send Content-Encoding: identity
      if (headers.get("content-encoding")?.toLowerCase() === "identity") {
        headers.delete("content-encoding");
      }

      // Strongly suggest no transforms on downloads
      const cc = headers.get("cache-control");
      headers.set("Cache-Control", `${cc ?? "no-cache, no-store, must-revalidate"}, no-transform`);

      // Add CORS
      headers.set("Access-Control-Allow-Origin", "*");
      headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
      headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");

      // Stream the body through untouched
      return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers,
      });
    }

    return upstream;
  },
}