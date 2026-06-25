const DEFAULT_EVENT_TYPE = "macos-launchpad-notarization-complete";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },
};

export async function handleRequest(request, env) {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/health") {
    return json({ status: "ok" });
  }

  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  const pathMatch = url.pathname.match(/^\/apple-notary\/([^/]+)$/);
  if (!pathMatch) {
    return json({ error: "Not found" }, 404);
  }

  if (!env.WEBHOOK_TOKEN) {
    return json({ error: "Worker is missing WEBHOOK_TOKEN" }, 500);
  }

  if (pathMatch[1] !== env.WEBHOOK_TOKEN) {
    return json({ error: "Not found" }, 404);
  }

  const sourceRunId = url.searchParams.get("source_run_id") || "";
  const version = url.searchParams.get("version") || "";
  const repository = url.searchParams.get("repository") || `${env.GITHUB_OWNER}/${env.GITHUB_REPO}`;

  if (!sourceRunId) {
    return json({ error: "Missing source_run_id query parameter" }, 400);
  }

  if (repository !== `${env.GITHUB_OWNER}/${env.GITHUB_REPO}`) {
    return json({ error: "Unexpected repository" }, 400);
  }

  const payload = await readApplePayload(request);
  const submissionId = extractSubmissionId(payload.json);
  const dispatchResult = await createRepositoryDispatch(env, {
    sourceRunId,
    version,
    submissionId,
    applePayload: payload.summary,
  });

  if (!dispatchResult.ok) {
    return json(
      {
        error: "GitHub dispatch failed",
        status: dispatchResult.status,
        body: dispatchResult.body,
      },
      502
    );
  }

  return json(
    {
      accepted: true,
      event_type: env.GITHUB_EVENT_TYPE || DEFAULT_EVENT_TYPE,
      source_run_id: sourceRunId,
      version,
      submission_id: submissionId || null,
    },
    202
  );
}

async function readApplePayload(request) {
  const contentType = request.headers.get("content-type") || "";
  const text = await request.text();
  let parsed = null;

  if (text && contentType.toLowerCase().includes("json")) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
  }

  return {
    json: parsed,
    summary: {
      content_type: contentType,
      body: truncateForDispatch(parsed ? JSON.stringify(parsed) : text),
      truncated: text.length > 4096,
    },
  };
}

function truncateForDispatch(value) {
  return value.length > 4096 ? value.slice(0, 4096) : value;
}

export function extractSubmissionId(value, seen = new WeakSet()) {
  if (!value || typeof value !== "object") {
    return "";
  }

  if (seen.has(value)) {
    return "";
  }
  seen.add(value);

  for (const [key, candidate] of Object.entries(value)) {
    if (typeof candidate === "string" && isSubmissionIdKey(key) && UUID_PATTERN.test(candidate)) {
      return candidate;
    }
  }

  for (const candidate of Object.values(value)) {
    if (candidate && typeof candidate === "object") {
      const nested = extractSubmissionId(candidate, seen);
      if (nested) {
        return nested;
      }
    }
  }

  return "";
}

function isSubmissionIdKey(key) {
  const normalized = key.toLowerCase().replace(/[-_]/g, "");
  return normalized === "submissionid" || normalized === "id";
}

async function createRepositoryDispatch(env, payload) {
  const installationToken = await createInstallationToken(env);
  if (!installationToken.ok) {
    return installationToken;
  }

  const owner = env.GITHUB_OWNER || "fluxzero-io";
  const repo = env.GITHUB_REPO || "fluxzero-cli";
  const eventType = env.GITHUB_EVENT_TYPE || DEFAULT_EVENT_TYPE;
  const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/dispatches`, {
    method: "POST",
    headers: {
      "Accept": "application/vnd.github+json",
      "Authorization": `Bearer ${installationToken.token}`,
      "Content-Type": "application/json",
      "User-Agent": "fluxzero-notary-webhook",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: JSON.stringify({
      event_type: eventType,
      client_payload: {
        source: "apple-notary-webhook",
        source_run_id: payload.sourceRunId,
        version: payload.version,
        submission_id: payload.submissionId,
        apple_payload: payload.applePayload,
        received_at: new Date().toISOString(),
      },
    }),
  });

  if (response.ok) {
    return { ok: true, status: response.status, body: "" };
  }

  return {
    ok: false,
    status: response.status,
    body: await response.text(),
  };
}

async function createInstallationToken(env) {
  const appId = env.GITHUB_APP_ID || "";
  const installationId = env.GITHUB_APP_INSTALLATION_ID || "";
  const privateKeyBase64 = env.GITHUB_APP_PRIVATE_KEY_BASE64 || "";

  if (!appId) {
    return { ok: false, status: 500, body: "Worker is missing GITHUB_APP_ID" };
  }
  if (!installationId) {
    return { ok: false, status: 500, body: "Worker is missing GITHUB_APP_INSTALLATION_ID" };
  }
  if (!privateKeyBase64) {
    return { ok: false, status: 500, body: "Worker is missing GITHUB_APP_PRIVATE_KEY_BASE64" };
  }

  let jwt;
  try {
    jwt = await createGitHubAppJwt(appId, privateKeyBase64);
  } catch (error) {
    return {
      ok: false,
      status: 500,
      body: `Could not create GitHub App JWT: ${error instanceof Error ? error.message : String(error)}`,
    };
  }
  const owner = env.GITHUB_OWNER || "fluxzero-io";
  const repo = env.GITHUB_REPO || "fluxzero-cli";
  const response = await fetch(`https://api.github.com/app/installations/${installationId}/access_tokens`, {
    method: "POST",
    headers: {
      "Accept": "application/vnd.github+json",
      "Authorization": `Bearer ${jwt}`,
      "Content-Type": "application/json",
      "User-Agent": "fluxzero-notary-webhook",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: JSON.stringify({
      repositories: [repo],
      permissions: {
        contents: "write",
      },
    }),
  });

  if (!response.ok) {
    return {
      ok: false,
      status: response.status,
      body: await response.text(),
    };
  }

  const body = await response.json();
  if (!body.token) {
    return { ok: false, status: 502, body: "GitHub App token response did not include a token" };
  }

  return { ok: true, status: response.status, token: body.token };
}

export async function createGitHubAppJwt(appId, privateKeyBase64, now = Math.floor(Date.now() / 1000)) {
  const header = {
    alg: "RS256",
    typ: "JWT",
  };
  const payload = {
    iat: now - 60,
    exp: now + 540,
    iss: String(appId),
  };
  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(payload)}`;
  const privateKey = await importPrivateKey(privateKeyBase64);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    privateKey,
    new TextEncoder().encode(signingInput)
  );

  return `${signingInput}.${base64UrlEncodeBytes(new Uint8Array(signature))}`;
}

async function importPrivateKey(privateKeyBase64) {
  const pem = decodePrivateKeySecret(privateKeyBase64).trim();
  const isPkcs1 = pem.includes("-----BEGIN RSA PRIVATE KEY-----");
  const isPkcs8 = pem.includes("-----BEGIN PRIVATE KEY-----");

  if (!isPkcs1 && !isPkcs8) {
    throw new Error("Expected a PEM encoded private key");
  }

  const derBase64 = pem
    .replace(/-----BEGIN (RSA )?PRIVATE KEY-----/g, "")
    .replace(/-----END (RSA )?PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const keyDer = base64ToBytes(derBase64);
  const pkcs8Der = isPkcs1 ? wrapPkcs1RsaPrivateKeyInPkcs8(keyDer) : keyDer;

  return crypto.subtle.importKey(
    "pkcs8",
    pkcs8Der,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"]
  );
}

function decodePrivateKeySecret(value) {
  if (value.includes("-----BEGIN ")) {
    return value;
  }

  return base64Decode(value);
}

function wrapPkcs1RsaPrivateKeyInPkcs8(pkcs1Der) {
  const rsaEncryptionAlgorithm = derSequence(
    new Uint8Array([0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01]),
    new Uint8Array([0x05, 0x00])
  );

  return derSequence(
    new Uint8Array([0x02, 0x01, 0x00]),
    rsaEncryptionAlgorithm,
    derEncode(0x04, pkcs1Der)
  );
}

function derSequence(...parts) {
  return derEncode(0x30, concatBytes(...parts));
}

function derEncode(tag, content) {
  return concatBytes(new Uint8Array([tag]), derLength(content.length), content);
}

function derLength(length) {
  if (length < 128) {
    return new Uint8Array([length]);
  }

  const bytes = [];
  let remaining = length;
  while (remaining > 0) {
    bytes.unshift(remaining & 0xff);
    remaining >>= 8;
  }

  return new Uint8Array([0x80 | bytes.length, ...bytes]);
}

function concatBytes(...parts) {
  const result = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;

  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }

  return result;
}

function base64UrlEncodeJson(value) {
  return base64UrlEncodeBytes(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlEncodeBytes(bytes) {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }

  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function base64Decode(value) {
  return new TextDecoder().decode(base64ToArrayBuffer(value));
}

function base64ToArrayBuffer(value) {
  return base64ToBytes(value).buffer;
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
    },
  });
}
