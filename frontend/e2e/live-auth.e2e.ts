import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { beforeAll, describe, expect, it } from "vitest";

import {
  ApiRequestError,
  api,
  clearSession,
  getAccessToken,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
} from "@/lib/api/client";
import { authApi, geoApi, platformApi } from "@/lib/api/endpoints";

/**
 * Phase 2 exit criteria, verified through the code the browser actually runs.
 *
 * These specs import the real API client rather than issuing raw fetches, so a
 * pass means the client's own auth handling — header injection, the 401 refresh
 * path, error envelope decoding — works against the live backend, not that curl
 * can reach it.
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const PASSWORD = "LiveE2e-Verify!2026";
const email = `fe-e2e+${Date.now()}@citypulse.local`;

/**
 * The backend allows 10 requests per minute per IP to /api/v1/auth/**. This
 * suite spends 7, so it only needs to start on a window that is not already
 * exhausted by a previous run.
 */
async function waitForCleanRateLimitWindow(): Promise<void> {
  const deadline = Date.now() + 75_000;
  while (Date.now() < deadline) {
    const response = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: "probe-not-a-real-token" }),
    });
    // Anything other than a 429 means the window has room. A rejected probe
    // token is the expected, harmless outcome.
    if (response.status !== 429) return;
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
  throw new Error("rate-limit window never cleared");
}

/** Reads CITYPULSE_JWT_SECRET from the repo .env so a token can be re-signed. */
function jwtSecret(): string {
  const envPath = path.resolve(import.meta.dirname, "../../.env");
  const line = readFileSync(envPath, "utf8")
    .split("\n")
    .find((l) => l.startsWith("CITYPULSE_JWT_SECRET="));
  if (!line) throw new Error("CITYPULSE_JWT_SECRET not found in .env");
  return line.slice("CITYPULSE_JWT_SECRET=".length).trim().replace(/^"|"$/g, "");
}

const b64url = (input: string | Buffer) => Buffer.from(input).toString("base64url");

/**
 * Re-signs a real access token with its `exp` moved into the past.
 *
 * Using the genuine claims and the genuine signing key matters: the backend
 * must reject this token specifically for being expired, not for a bad
 * signature or a malformed body. That is the condition the refresh path exists
 * to handle.
 */
function expireToken(accessToken: string): string {
  const [, payloadPart] = accessToken.split(".");
  const claims = JSON.parse(Buffer.from(payloadPart, "base64url").toString("utf8"));

  const past = Math.floor(Date.now() / 1000) - 3600;
  const expired = { ...claims, iat: past - 60, exp: past };

  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = b64url(JSON.stringify(expired));
  const signature = createHmac("sha256", jwtSecret())
    .update(`${header}.${payload}`)
    .digest("base64url");

  return `${header}.${payload}.${signature}`;
}

describe("live stack: auth and data through the real API client", () => {
  beforeAll(async () => {
    const health = await fetch(`${API_BASE}/actuator/health`).catch(() => null);
    if (!health?.ok) {
      throw new Error(`backend is not reachable at ${API_BASE} — start it before running test:e2e`);
    }
    await waitForCleanRateLimitWindow();
    clearSession();
  });

  it("serves public platform metadata without a session", async () => {
    const info = await platformApi.info();
    expect(info).toBeTruthy();
    expect(typeof info.version).toBe("string");
  });

  it("signs a new user up and logs them in", async () => {
    await authApi.signup({ email, password: PASSWORD, fullName: "Frontend E2E" });

    const tokens = await authApi.login({ email, password: PASSWORD });
    expect(tokens.accessToken).toBeTruthy();
    expect(tokens.refreshToken).toBeTruthy();

    setAccessToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    expect(getRefreshToken()).toBe(tokens.refreshToken);
  });

  it("reaches a protected endpoint and identifies the caller", async () => {
    const profile = await authApi.me();
    expect(profile.email).toBe(email);
    expect(profile.roles).toContain("VIEWER");
  });

  it("refreshes an expired access token transparently, with no visible failure", async () => {
    const live = getAccessToken();
    expect(live).toBeTruthy();

    // Prove the backend really rejects the expired token, so the next
    // assertion cannot pass by the token still being valid.
    const expired = expireToken(live!);
    const direct = await fetch(`${API_BASE}/api/v1/auth/me`, {
      headers: { Authorization: `Bearer ${expired}` },
    });
    expect(direct.status).toBe(401);

    setAccessToken(expired);
    const beforeRefresh = getRefreshToken();

    // The caller does nothing special — the client is expected to absorb the
    // 401, refresh, and retry.
    const profile = await authApi.me();

    expect(profile.email).toBe(email);
    expect(getAccessToken()).not.toBe(expired);
    expect(getRefreshToken()).not.toBe(beforeRefresh); // rotated, not reused
  });

  it("surfaces a permission denial as a typed 403 rather than a crash", async () => {
    const denied = await api.get("/api/v1/users").catch((e: unknown) => e);
    expect(denied).toBeInstanceOf(ApiRequestError);
    const error = denied as ApiRequestError;
    expect(error.isForbidden).toBe(true);
    expect(error.status).toBe(403);
    expect(error.message).toBeTruthy();
  });

  it("loads seeded cities and their zones", async () => {
    const cities = await geoApi.listCities();
    expect(cities.length).toBeGreaterThan(0);

    const zones = await geoApi.listZones(cities[0].id);
    expect(zones.length).toBeGreaterThan(0);
    expect(zones[0].name).toBeTruthy();
  });

  it("ends the session on logout and refuses the dead refresh token", async () => {
    const refreshToken = getRefreshToken();
    expect(refreshToken).toBeTruthy();

    await authApi.logout(refreshToken!);
    clearSession();

    const replay = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    expect(replay.status).toBe(401);
  });
});
