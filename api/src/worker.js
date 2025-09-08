import { Container, getContainer } from "@cloudflare/containers";

export class FluxzeroCliApi extends Container {
  defaultPort = 8080;
  sleepAfter = "10m";
  
  async startup() {
    // Container startup hook - nothing special needed for our API
    console.log("Fluxzero CLI API container starting up");
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    // Health check endpoint handled by worker directly
    if (url.pathname === '/health') {
      return new Response('OK', { 
        status: 200,
        headers: { 'Content-Type': 'text/plain' }
      });
    }
    
    // Get or create container instance
    const container = await getContainer(env.FLUXZERO_CLI_API, "FluxzeroCliApi");
    
    // Forward all API requests to the container
    return container.fetch(request);
  }
};