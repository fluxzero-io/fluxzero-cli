import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { createGitHubAppJwt, extractSubmissionId, handleRequest } from "../src/index.js";

test("extractSubmissionId finds common Apple-style identifiers", () => {
  assert.equal(
    extractSubmissionId({
      event: {
        submissionId: "d0d37a38-dc80-4603-bca9-80705a49cbbd",
      },
    }),
    "d0d37a38-dc80-4603-bca9-80705a49cbbd"
  );

  assert.equal(
    extractSubmissionId({
      data: {
        id: "d0d37a38-dc80-4603-bca9-80705a49cbbd",
      },
    }),
    "d0d37a38-dc80-4603-bca9-80705a49cbbd"
  );
});

test("handleRequest rejects missing token path", async () => {
  const response = await handleRequest(
    new Request("https://example.com/apple-notary/wrong?source_run_id=1", { method: "POST" }),
    {
      WEBHOOK_TOKEN: "secret",
      GITHUB_OWNER: "fluxzero-io",
      GITHUB_REPO: "fluxzero-cli",
    }
  );

  assert.equal(response.status, 404);
});

test("handleRequest rejects missing source_run_id", async () => {
  const response = await handleRequest(
    new Request("https://example.com/apple-notary/secret", { method: "POST" }),
    {
      WEBHOOK_TOKEN: "secret",
      GITHUB_OWNER: "fluxzero-io",
      GITHUB_REPO: "fluxzero-cli",
    }
  );

  assert.equal(response.status, 400);
});

test("createGitHubAppJwt signs with a PKCS#1 GitHub App key", async () => {
  const privateKeyBase64 = createPrivateKeyBase64("pkcs1");
  const jwt = await createGitHubAppJwt("123456", privateKeyBase64, 1_800_000_000);
  const parts = jwt.split(".");

  assert.equal(parts.length, 3);
  assert.deepEqual(decodeJwtPart(parts[0]), { alg: "RS256", typ: "JWT" });
  assert.deepEqual(decodeJwtPart(parts[1]), {
    iat: 1_799_999_940,
    exp: 1_800_000_540,
    iss: "123456",
  });
});

test("handleRequest dispatches accepted notary callbacks to GitHub", async (t) => {
  const originalFetch = globalThis.fetch;
  const privateKeyBase64 = createPrivateKeyBase64("pkcs8");
  let call = 0;
  t.after(() => {
    globalThis.fetch = originalFetch;
  });

  globalThis.fetch = async (url, init) => {
    call += 1;

    if (call === 1) {
      assert.equal(url, "https://api.github.com/app/installations/987654/access_tokens");
      assert.equal(init.method, "POST");
      assert.match(init.headers.Authorization, /^Bearer [^.]+\.[^.]+\.[^.]+$/);

      const body = JSON.parse(init.body);
      assert.deepEqual(body, {
        repositories: ["fluxzero-cli"],
        permissions: {
          contents: "write",
        },
      });

      return Response.json({ token: "installation-token" }, { status: 201 });
    }

    assert.equal(call, 2);
    assert.equal(url, "https://api.github.com/repos/fluxzero-io/fluxzero-cli/dispatches");
    assert.equal(init.method, "POST");
    assert.equal(init.headers.Authorization, "Bearer installation-token");

    const body = JSON.parse(init.body);
    assert.equal(body.event_type, "macos-launchpad-notarization-complete");
    assert.equal(body.client_payload.source_run_id, "12345");
    assert.equal(body.client_payload.version, "1.3.14");
    assert.equal(body.client_payload.submission_id, "d0d37a38-dc80-4603-bca9-80705a49cbbd");

    return new Response(null, { status: 204 });
  };

  const response = await handleRequest(
    new Request("https://example.com/apple-notary/secret?source_run_id=12345&version=1.3.14&repository=fluxzero-io/fluxzero-cli", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        submissionId: "d0d37a38-dc80-4603-bca9-80705a49cbbd",
      }),
    }),
    {
      WEBHOOK_TOKEN: "secret",
      GITHUB_APP_ID: "123456",
      GITHUB_APP_INSTALLATION_ID: "987654",
      GITHUB_APP_PRIVATE_KEY_BASE64: privateKeyBase64,
      GITHUB_OWNER: "fluxzero-io",
      GITHUB_REPO: "fluxzero-cli",
      GITHUB_EVENT_TYPE: "macos-launchpad-notarization-complete",
    }
  );

  assert.equal(response.status, 202);
  assert.equal(call, 2);
});

function createPrivateKeyBase64(type) {
  const { privateKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  const pem = privateKey.export({
    type,
    format: "pem",
  });

  return Buffer.from(pem).toString("base64");
}

function decodeJwtPart(value) {
  return JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
}
