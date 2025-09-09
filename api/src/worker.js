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

    if (url.pathname === "/health") {
      return new Response("OK", {
        status: 200,
        headers: { "Content-Type": "text/plain" },
      });
    }

    // Option A: sticky instance
    // const container = env.FLUXZERO_CLI_API.getByName("cli-api");

    // Option B: simple load balancing
    const container = getRandom(env.FLUXZERO_CLI_API, 3);
    return container.fetch(request);
  },
};