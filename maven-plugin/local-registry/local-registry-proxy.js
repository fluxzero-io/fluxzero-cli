#!/usr/bin/env node
const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const https = require("https");

const listenHost = process.env.FLUXZERO_LOCAL_LISTEN_HOST || "0.0.0.0";
const listenPort = Number(process.env.FLUXZERO_LOCAL_TLS_PORT || "8443");
const backendHost = process.env.FLUXZERO_LOCAL_BACKEND_HOST || "zot";
const backendPort = Number(process.env.FLUXZERO_LOCAL_BACKEND_PORT || "5000");
const secret = process.env.FLUXZERO_LOCAL_CI_TOKEN_SECRET || "local-dev-ci-token-secret";
const certPfx = process.env.FLUXZERO_LOCAL_CERT_PFX || "/certs/server.p12";
const certPassphrase = process.env.FLUXZERO_LOCAL_CERT_PASSPHRASE || "changeit";
const metricsPath = process.env.FLUXZERO_LOCAL_PROXY_METRICS || "/metrics/proxy-metrics.ndjson";

function unauthorized(response, message = "authentication required") {
  response.writeHead(401, {
    "content-type": "text/plain",
    "www-authenticate": 'Basic realm="Fluxzero local registry"',
  });
  response.end(message);
}

function base64urlJson(value) {
  return JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
}

function verifyToken(token) {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("invalid token format");
  }
  const data = `${parts[0]}.${parts[1]}`;
  const expected = crypto.createHmac("sha256", secret).update(data).digest("base64url");
  if (
    expected.length !== parts[2].length ||
    !crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(parts[2]))
  ) {
    throw new Error("invalid token signature");
  }

  const payload = base64urlJson(parts[1]);
  const now = Math.floor(Date.now() / 1000);
  if (!payload.exp || payload.exp < now) {
    throw new Error("token expired");
  }
  if (!payload.teamId || !payload.imageName) {
    throw new Error("token must include teamId and imageName");
  }
  return payload;
}

function authenticate(request) {
  const header = request.headers.authorization || "";
  const [scheme, encoded] = header.split(/\s+/, 2);
  if (scheme?.toLowerCase() !== "basic" || !encoded) {
    return null;
  }

  const decoded = Buffer.from(encoded, "base64").toString("utf8");
  const separator = decoded.indexOf(":");
  const password = separator >= 0 ? decoded.substring(separator + 1) : decoded;
  return verifyToken(password);
}

function targetPath(originalUrl, payload) {
  const [pathOnly, query] = originalUrl.split("?", 2);
  if (pathOnly === "/v2" || pathOnly === "/v2/") {
    return { path: `/v2/${query ? `?${query}` : ""}`, clientUsesTeamPrefix: true };
  }
  if (!pathOnly.startsWith("/v2/")) {
    return { path: originalUrl, clientUsesTeamPrefix: true };
  }

  const segments = pathOnly.substring("/v2/".length).split("/");
  const clientUsesTeamPrefix = segments[0] === payload.teamId;
  const imageName = clientUsesTeamPrefix ? segments[1] : segments[0];
  if (imageName !== payload.imageName) {
    throw new Error(`token is for image '${payload.imageName}', not '${imageName}'`);
  }

  const backendSegments = clientUsesTeamPrefix ? segments : [payload.teamId, ...segments];
  return {
    path: `/v2/${backendSegments.join("/")}${query ? `?${query}` : ""}`,
    clientUsesTeamPrefix,
  };
}

function rewriteLocation(location, publicHost, payload, clientUsesTeamPrefix) {
  let rewritten = location.replace(`http://${backendHost}:${backendPort}`, `https://${publicHost}`);
  rewritten = rewritten.replace(`https://${backendHost}:${backendPort}`, `https://${publicHost}`);
  if (!clientUsesTeamPrefix) {
    rewritten = rewritten.replace(`/v2/${payload.teamId}/`, "/v2/");
  }
  return rewritten;
}

function logMetric(metric) {
  const directorySeparator = metricsPath.lastIndexOf("/");
  if (directorySeparator > 0) {
    fs.mkdirSync(metricsPath.substring(0, directorySeparator), { recursive: true });
  }
  fs.appendFileSync(metricsPath, JSON.stringify({ time: new Date().toISOString(), ...metric }) + "\n");
}

const server = https.createServer({
  pfx: fs.readFileSync(certPfx),
  passphrase: certPassphrase,
}, (clientReq, clientRes) => {
  let payload;
  try {
    payload = authenticate(clientReq);
  } catch (error) {
    unauthorized(clientRes, error.message);
    return;
  }
  if (!payload) {
    unauthorized(clientRes);
    return;
  }

  let requestTarget;
  try {
    requestTarget = targetPath(clientReq.url, payload);
  } catch (error) {
    clientRes.writeHead(403, { "content-type": "text/plain" });
    clientRes.end(error.message);
    return;
  }

  let requestBytes = 0;
  const headers = { ...clientReq.headers, host: `${backendHost}:${backendPort}` };
  delete headers.authorization;
  const upstreamReq = http.request({
    host: backendHost,
    port: backendPort,
    method: clientReq.method,
    path: requestTarget.path,
    headers,
  }, (upstreamRes) => {
    const responseHeaders = { ...upstreamRes.headers };
    if (responseHeaders.location) {
      responseHeaders.location = rewriteLocation(
        responseHeaders.location,
        clientReq.headers.host || `127.0.0.1:${listenPort}`,
        payload,
        requestTarget.clientUsesTeamPrefix
      );
    }
    clientRes.writeHead(upstreamRes.statusCode, responseHeaders);
    upstreamRes.pipe(clientRes);
  });

  upstreamReq.on("error", (error) => {
    clientRes.writeHead(502, { "content-type": "text/plain" });
    clientRes.end(`local registry proxy error: ${error.message}`);
  });

  clientReq.on("data", (chunk) => {
    requestBytes += chunk.length;
  });
  clientReq.on("end", () => {
    logMetric({
      method: clientReq.method,
      path: clientReq.url,
      targetPath: requestTarget.path,
      teamId: payload.teamId,
      imageName: payload.imageName,
      requestBytes,
    });
  });

  clientReq.pipe(upstreamReq);
});

server.listen(listenPort, listenHost, () => {
  console.log(`local Fluxzero registry proxy listening on https://127.0.0.1:${listenPort}`);
});
