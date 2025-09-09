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

    // Handle CORS preflight requests for /api/* endpoints
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

    // Option A: sticky instance
    // const container = env.FLUXZERO_CLI_API.getByName("cli-api");

    // Option B: simple load balancing
    const container = await getRandom(env.FLUXZERO_CLI_API, 3);
    const response = await container.fetch(request);

    // Add CORS headers to /api/* responses
    if (url.pathname.startsWith('/api/')) {
      const corsHeaders = {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      };

      // Create new response with CORS headers
      return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers: {
          ...Object.fromEntries(response.headers),
          ...corsHeaders,
        },
      });
    }

    return response;
  },
};