#!/usr/bin/env node
const crypto = require("crypto");

const teamId = process.argv[2] || process.env.FLUXZERO_LOCAL_TEAM_ID || "team-a";
const packageName = process.argv[3] || process.env.FLUXZERO_LOCAL_PACKAGE_NAME || "plain-java";
const secret = process.env.FLUXZERO_LOCAL_CI_TOKEN_SECRET || "local-dev-ci-token-secret";
const organisationId = process.env.FLUXZERO_LOCAL_ORGANISATION_ID || "local-org";
const installationId = Number(process.env.FLUXZERO_LOCAL_INSTALLATION_ID || "1");
const now = Math.floor(Date.now() / 1000);

function base64url(value) {
  const input = typeof value === "string" ? value : JSON.stringify(value);
  return Buffer.from(input).toString("base64url");
}

const header = { alg: "HS256", typ: "JWT" };
const payload = {
  iss: "https://fluxzero.io",
  installationId,
  teamId,
  organisationId,
  packageName,
  iat: now,
  exp: now + 3600,
};
const data = `${base64url(header)}.${base64url(payload)}`;
const signature = crypto.createHmac("sha256", secret).update(data).digest("base64url");

process.stdout.write(`${data}.${signature}`);
