import { api } from "./client";
import type {
  ApiKeyCreated,
  ApiKeySummary,
  ScopeCatalogue,
  DataSourceList,
  PipelineHealth,
  CityHistory,
  AlertDetail,
  AlertStatus,
  AlertSummary,
  AuthTokens,
  City,
  CitySnapshot,
  PageResponse,
  PlatformInfo,
  SignupResult,
  UserProfile,
  Zone,
  ZoneHistory,
  AccuracyReport,
  CityOutlook,
  ForecastMetric,
  ZoneForecast,
  RunScenarioRequest,
  SimulationDetail,
  SimulationSummary,
  AnomalyDetail,
  Correlation,
  InsightsSummary,
  MemoryRecall,
} from "./types";

/**
 * One function per backend endpoint. Components call these rather than building
 * URLs, so a route change is a single edit and every call site stays typed.
 */

export const authApi = {
  signup: (body: { email: string; password: string; fullName: string; organization?: string }) =>
    api.anonymous.post<SignupResult>("/api/v1/auth/signup", body),

  login: (body: { email: string; password: string }) =>
    api.anonymous.post<AuthTokens>("/api/v1/auth/login", body),

  logout: (refreshToken: string) =>
    api.anonymous.post<void>("/api/v1/auth/logout", { refreshToken }),

  forgotPassword: (email: string) =>
    api.anonymous.post<void>("/api/v1/auth/forgot-password", { email }),

  resetPassword: (body: { token: string; newPassword: string }) =>
    api.anonymous.post<void>("/api/v1/auth/reset-password", body),

  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    api.post<void>("/api/v1/auth/change-password", body),

  me: () => api.get<UserProfile>("/api/v1/auth/me"),
};

export const geoApi = {
  listCities: (activeOnly = true) =>
    api.get<City[]>(`/api/v1/cities?activeOnly=${activeOnly}`),

  getCity: (cityId: string) => api.get<City>(`/api/v1/cities/${cityId}`),

  getCityBySlug: (slug: string) => api.get<City>(`/api/v1/cities/by-slug/${slug}`),

  listZones: (cityId: string, activeOnly = true) =>
    api.get<Zone[]>(`/api/v1/cities/${cityId}/zones?activeOnly=${activeOnly}`),

  getZone: (zoneId: string) => api.get<Zone>(`/api/v1/zones/${zoneId}`),
};

export const dataSourceApi = {
  list: () => api.get<DataSourceList>("/api/v1/data-sources"),

  /** Pipeline quality: what arrived against what was kept. */
  health: () => api.get<PipelineHealth>("/api/v1/data-sources/health"),
};

export const apiKeyApi = {
  list: () => api.get<ApiKeySummary[]>("/api/v1/api-keys"),
  scopes: () => api.get<ScopeCatalogue>("/api/v1/api-keys/scopes"),
  create: (body: { name: string; description?: string; scopes: string[]; expiresInDays?: number }) =>
    api.post<ApiKeyCreated>("/api/v1/api-keys", body),
  /** Reason is optional and the endpoint accepts a bodyless DELETE. */
  revoke: (keyId: string) => api.delete<ApiKeySummary>(`/api/v1/api-keys/${keyId}`),
};

export const platformApi = {
  info: () => api.anonymous.get<PlatformInfo>("/api/v1/meta/platform"),
};

export const liveApi = {
  /**
   * One consistent snapshot: KPIs, every zone, and how fresh the data is.
   *
   * Fetched as a single payload rather than per-widget so the map and the tiles
   * above it cannot disagree — separate calls would drift by a window.
   */
  snapshot: (citySlug: string, signal?: AbortSignal) =>
    api.get<CitySnapshot>(`/api/v1/live/by-slug/${citySlug}`, signal),

  history: (zoneId: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const query = params.toString();
    return api.get<ZoneHistory>(
      `/api/v1/live/zones/${zoneId}/history${query ? `?${query}` : ""}`,
    );
  },

  /**
   * Exchanges the session for a one-minute, single-use stream ticket.
   *
   * The browser's EventSource cannot send an Authorization header, and putting
   * the access token in the stream URL would leak it into server logs, browser
   * history and Referer headers. The ticket is the credential that is safe to
   * put in a query string because it is worthless a minute later.
   */
  /** A city's aggregated series, for the dashboard's trends and sparklines. */
  cityHistory: (citySlug: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const query = params.toString();
    return api.get<CityHistory>(
      `/api/v1/live/by-slug/${citySlug}/history${query ? `?${query}` : ""}`,
    );
  },

  streamTicket: (citySlug: string) =>
    api.post<{ ticket: string; expiresInSeconds: number }>(
      `/api/v1/live/by-slug/${citySlug}/stream-ticket`,
    ),
};

export const alertApi = {
  list: (params: { cityId?: string; status?: AlertStatus; openOnly?: boolean; size?: number } = {}) => {
    const query = new URLSearchParams();
    if (params.cityId) query.set("cityId", params.cityId);
    if (params.status) query.set("status", params.status);
    if (params.openOnly !== undefined) query.set("openOnly", String(params.openOnly));
    query.set("size", String(params.size ?? 50));
    return api.get<PageResponse<AlertDetail>>(`/api/v1/alerts?${query}`);
  },

  summary: (cityId?: string) =>
    api.get<AlertSummary>(`/api/v1/alerts/summary${cityId ? `?cityId=${cityId}` : ""}`),

  get: (alertId: string) => api.get<AlertDetail>(`/api/v1/alerts/${alertId}`),

  setStatus: (alertId: string, status: AlertStatus, note?: string) =>
    api.patch<AlertDetail>(`/api/v1/alerts/${alertId}/status`, { status, note }),
};

export const forecastApi = {
  /** Every horizon for one zone, each with the measured error behind its confidence. */
  forZone: (zoneId: string, metric: ForecastMetric = "occupancy_ratio") =>
    api.get<ZoneForecast>(`/api/v1/forecasts/zones/${zoneId}?metric=${metric}`),

  forCity: (slug: string, metric: ForecastMetric = "occupancy_ratio", horizonMinutes = 60) =>
    api.get<CityOutlook>(
      `/api/v1/forecasts/cities/${slug}?metric=${metric}&horizonMinutes=${horizonMinutes}`,
    ),

  /** Production error beside holdout error — the honest test of the confidence. */
  accuracy: () => api.get<AccuracyReport>("/api/v1/forecasts/accuracy"),
};

export const simulationApi = {
  /** Runs a scenario and returns the outcome; the engine is synchronous. */
  run: (request: RunScenarioRequest) =>
    api.post<SimulationDetail>("/api/v1/simulations", request),

  get: (id: string) => api.get<SimulationDetail>(`/api/v1/simulations/${id}`),

  history: (citySlug: string, size = 20) =>
    api.get<PageResponse<SimulationSummary>>(
      `/api/v1/simulations?citySlug=${citySlug}&size=${size}`,
    ),
};

export const intelligenceApi = {
  /** Anomalies ordered by how far from normal they were. */
  anomalies: (citySlug: string, hours = 24, size = 50) =>
    api.get<PageResponse<AnomalyDetail>>(
      `/api/v1/anomalies?citySlug=${citySlug}&hours=${hours}&size=${size}`,
    ),

  correlations: (citySlug: string) =>
    api.get<Correlation[]>(`/api/v1/anomalies/correlations?citySlug=${citySlug}`),

  /** What historically followed conditions like these. */
  memory: (
    citySlug: string,
    params: { rainBand?: string; dayType?: string; hourBand?: string; hadEvent?: boolean; incidentBand?: string } = {},
  ) => {
    const query = new URLSearchParams({ citySlug });
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined) query.set(key, String(value));
    });
    return api.get<MemoryRecall>(`/api/v1/anomalies/memory?${query}`);
  },

  insights: (citySlug: string) =>
    api.get<InsightsSummary>(`/api/v1/anomalies/insights?citySlug=${citySlug}`),
};
