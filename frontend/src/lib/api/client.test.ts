import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiRequestError,
  NetworkError,
  clearSession,
  getAccessToken,
  request,
  setAccessToken,
  setRefreshToken,
} from "./client";

/**
 * API client behaviour that the UI depends on: error shape, transparent refresh,
 * and the single-flight guarantee that stops concurrent 401s from tripping the
 * backend's token reuse detection.
 */
describe("api client", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    clearSession();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function jsonResponse(status: number, body: unknown) {
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    } as Response;
  }

  it("unwraps the data field from a successful envelope", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { success: true, data: { slug: "noida" }, message: "ok" }),
    );

    await expect(request<{ slug: string }>("/api/v1/cities/x")).resolves.toEqual({ slug: "noida" });
  });

  it("throws ApiRequestError carrying the backend error code", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(403, {
        success: false,
        error: { code: "ACCESS_DENIED", message: "You do not have permission" },
      }),
    );

    await expect(request("/api/v1/cities")).rejects.toMatchObject({
      name: "ApiRequestError",
      status: 403,
      code: "ACCESS_DENIED",
    });
  });

  it("exposes validation failures as a field-keyed map", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(422, {
        success: false,
        error: {
          code: "VALIDATION_FAILED",
          message: "Request validation failed",
          fieldErrors: [{ field: "password", message: "Password is too short" }],
        },
      }),
    );

    try {
      await request("/api/v1/auth/signup", { method: "POST", body: {} });
      expect.unreachable("should have thrown");
    } catch (error) {
      expect(error).toBeInstanceOf(ApiRequestError);
      const apiError = error as ApiRequestError;
      expect(apiError.isValidation).toBe(true);
      expect(apiError.fieldErrors.password).toBe("Password is too short");
    }
  });

  it("reports a network failure distinctly from an HTTP error", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    await expect(request("/api/v1/cities")).rejects.toBeInstanceOf(NetworkError);
  });

  it("attaches the bearer token when one is set", async () => {
    setAccessToken("token-abc");
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { success: true, data: null }));

    await request("/api/v1/cities");

    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer token-abc");
  });

  it("omits the bearer token for anonymous requests", async () => {
    setAccessToken("token-abc");
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { success: true, data: null }));

    await request("/api/v1/auth/login", { method: "POST", body: {}, anonymous: true });

    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });

  it("refreshes once on a 401 and retries the original request", async () => {
    setAccessToken("expired");
    setRefreshToken("refresh-1");

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { success: false, error: { code: "INVALID_TOKEN", message: "expired" } }))
      .mockResolvedValueOnce(jsonResponse(200, {
        success: true,
        data: { accessToken: "fresh", refreshToken: "refresh-2" },
      }))
      .mockResolvedValueOnce(jsonResponse(200, { success: true, data: { ok: true } }));

    await expect(request<{ ok: boolean }>("/api/v1/cities")).resolves.toEqual({ ok: true });
    expect(getAccessToken()).toBe("fresh");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("refreshes only once for concurrent 401s", async () => {
    // The important property: presenting the same refresh token twice looks like
    // token theft to the backend and revokes the whole session family. Concurrent
    // callers must therefore share a single refresh.
    setAccessToken("expired");
    setRefreshToken("refresh-1");

    fetchMock.mockImplementation(async (url: string) => {
      if (url.includes("/auth/refresh")) {
        return jsonResponse(200, {
          success: true,
          data: { accessToken: "fresh", refreshToken: "refresh-2" },
        });
      }
      // Unauthorised until the token has been replaced.
      if (getAccessToken() === "expired") {
        return jsonResponse(401, { success: false, error: { code: "INVALID_TOKEN", message: "expired" } });
      }
      return jsonResponse(200, { success: true, data: { ok: true } });
    });

    await Promise.all([
      request("/api/v1/cities"),
      request("/api/v1/zones"),
      request("/api/v1/auth/me"),
    ]);

    const refreshCalls = fetchMock.mock.calls.filter((call) =>
      String(call[0]).includes("/auth/refresh"),
    );
    expect(refreshCalls).toHaveLength(1);
  });

  it("does not attempt a refresh when no refresh token is stored", async () => {
    setAccessToken("expired");

    fetchMock.mockResolvedValueOnce(
      jsonResponse(401, { success: false, error: { code: "INVALID_TOKEN", message: "expired" } }),
    );

    await expect(request("/api/v1/cities")).rejects.toBeInstanceOf(ApiRequestError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("clears the session when the refresh itself is rejected", async () => {
    setAccessToken("expired");
    setRefreshToken("revoked");

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { success: false, error: { code: "INVALID_TOKEN", message: "expired" } }))
      .mockResolvedValueOnce(jsonResponse(401, { success: false, error: { code: "INVALID_TOKEN", message: "revoked" } }));

    await expect(request("/api/v1/cities")).rejects.toMatchObject({ code: "SESSION_EXPIRED" });
    expect(getAccessToken()).toBeNull();
  });

  it("reports a non-JSON body as an unexpected response rather than a parse error", async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 502,
      json: async () => {
        throw new SyntaxError("Unexpected token <");
      },
    } as unknown as Response);

    await expect(request("/api/v1/cities")).rejects.toMatchObject({
      code: "UNEXPECTED_RESPONSE",
      status: 502,
    });
  });
});
