import type { ApiError, ApiResponse } from "./types";

/**
 * Typed API client with transparent access-token refresh.
 *
 * Token storage: the access token is held in module memory only, never in
 * localStorage or a non-httpOnly cookie, so an XSS payload cannot read it from
 * persistent storage. The refresh token is persisted in sessionStorage as a
 * deliberate trade-off — without it, every page reload would force a re-login,
 * which pushes users toward worse habits. sessionStorage is scoped to the tab
 * and cleared when it closes, which bounds the exposure. Moving refresh tokens
 * to httpOnly cookies is recorded as a hardening step in docs/SECURITY.md.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const REFRESH_STORAGE_KEY = "citypulse.refresh";

/** Thrown for any non-2xx response, carrying the backend's error envelope. */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: Record<string, string>;
  readonly requestId?: string;

  constructor(status: number, error: ApiError) {
    super(error.message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = error.code;
    this.requestId = error.requestId;
    this.fieldErrors = Object.fromEntries(
      (error.fieldErrors ?? []).map((fe) => [fe.field, fe.message]),
    );
  }

  /** True when the caller is authenticated but lacks the required permission. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isValidation(): boolean {
    return this.code === "VALIDATION_FAILED";
  }
}

/** Raised when the network itself fails, so the UI can distinguish it from a 4xx. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super("Could not reach the CityPulse API. Check your connection and try again.");
    this.name = "NetworkError";
    this.cause = cause;
  }
}

let accessToken: string | null = null;
/** Shared across concurrent 401s so a burst of requests triggers one refresh, not N. */
let refreshInFlight: Promise<string> | null = null;
let onSessionExpired: (() => void) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function setRefreshToken(token: string | null): void {
  if (typeof window === "undefined") return;
  if (token) {
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, token);
  } else {
    window.sessionStorage.removeItem(REFRESH_STORAGE_KEY);
  }
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.sessionStorage.getItem(REFRESH_STORAGE_KEY);
}

export function clearSession(): void {
  accessToken = null;
  setRefreshToken(null);
}

/** Registers the callback that redirects to sign-in when a session cannot be renewed. */
export function setSessionExpiredHandler(handler: (() => void) | null): void {
  onSessionExpired = handler;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Skips the Authorization header and the refresh-on-401 path. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

async function rawRequest(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (!options.anonymous && accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      method: options.method ?? "GET",
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch (cause) {
    // An aborted request is a caller decision, not a network fault.
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw new NetworkError(cause);
  }
}

/**
 * Exchanges the stored refresh token for a new pair.
 *
 * Concurrent callers share one in-flight request. Without that, several
 * simultaneous 401s would each present the same refresh token; the second
 * arrival would look like token reuse to the backend and revoke the entire
 * session family — signing the user out for doing nothing wrong.
 */
async function refreshAccessToken(): Promise<string> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) throw new Error("No refresh token available");

    const response = await rawRequest("/api/v1/auth/refresh", {
      method: "POST",
      body: { refreshToken },
      anonymous: true,
    });

    const payload = (await response.json()) as ApiResponse<{
      accessToken: string;
      refreshToken: string;
    }>;

    if (!response.ok || !payload.success || !payload.data) {
      clearSession();
      throw new ApiRequestError(
        response.status,
        payload.error ?? { code: "SESSION_EXPIRED", message: "Session expired" },
      );
    }

    setAccessToken(payload.data.accessToken);
    setRefreshToken(payload.data.refreshToken);
    return payload.data.accessToken;
  })();

  try {
    return await refreshInFlight;
  } finally {
    refreshInFlight = null;
  }
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await rawRequest(path, options);

  // One retry after a refresh. Never more, so a persistently rejecting endpoint
  // cannot become a refresh loop.
  if (response.status === 401 && !options.anonymous && getRefreshToken()) {
    try {
      await refreshAccessToken();
      response = await rawRequest(path, options);
    } catch {
      clearSession();
      onSessionExpired?.();
      throw new ApiRequestError(401, {
        code: "SESSION_EXPIRED",
        message: "Your session has expired. Please sign in again.",
      });
    }
  }

  if (response.status === 204) {
    return undefined as T;
  }

  let payload: ApiResponse<T>;
  try {
    payload = (await response.json()) as ApiResponse<T>;
  } catch {
    // A non-JSON body means something upstream (a proxy, a gateway) responded,
    // not the API. Report it as such rather than showing a parse error.
    throw new ApiRequestError(response.status, {
      code: "UNEXPECTED_RESPONSE",
      message: `The server returned an unexpected response (${response.status}).`,
    });
  }

  if (!response.ok || !payload.success) {
    throw new ApiRequestError(
      response.status,
      payload.error ?? { code: "UNKNOWN_ERROR", message: "An unexpected error occurred." },
    );
  }

  return payload.data as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: "PUT", body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: "PATCH", body }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  /** For endpoints reachable before a session exists. */
  anonymous: {
    get: <T>(path: string) => request<T>(path, { anonymous: true }),
    post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body, anonymous: true }),
  },
};
