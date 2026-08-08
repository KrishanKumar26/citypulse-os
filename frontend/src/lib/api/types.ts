/**
 * Types mirroring the backend contract. Hand-maintained rather than generated,
 * so that a backend change surfaces as a compile error here rather than as a
 * runtime surprise.
 *
 * Source of truth: the OpenAPI document at /v3/api-docs.
 */

/** The envelope every endpoint returns (PRD §28). */
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
  error?: ApiError;
}

export interface ApiError {
  code: string;
  message: string;
  fieldErrors?: FieldError[];
  requestId?: string;
  timestamp?: string;
}

export interface FieldError {
  field: string;
  message: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

// --- Authentication ---------------------------------------------------------

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  accessTokenExpiresAt: string;
  user: UserProfile;
}

export interface SignupResult {
  email: string;
  status: string;
  emailVerificationRequired: boolean;
  message: string;
}

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  organization: string | null;
  status: "PENDING_VERIFICATION" | "ACTIVE" | "SUSPENDED";
  emailVerified: boolean;
  roles: string[];
  /** Flattened permissions, used to hide controls. The API enforces them independently. */
  permissions: string[];
  lastLoginAt: string | null;
  createdAt: string;
}

// --- Geography --------------------------------------------------------------

export interface City {
  id: string;
  slug: string;
  name: string;
  country: string;
  countryCode: string;
  timezone: string;
  centerLatitude: string;
  centerLongitude: string;
  defaultZoom: number;
  population: number | null;
  areaSqKm: string | null;
  active: boolean;
  /** True when telemetry is synthetic. The UI must label it (PRD §42). */
  demoData: boolean;
  zoneCount: number;
}

export type ZoneType =
  | "RESIDENTIAL"
  | "COMMERCIAL"
  | "INDUSTRIAL"
  | "MIXED"
  | "TRANSIT_HUB"
  | "EDUCATIONAL"
  | "RECREATIONAL"
  | "AIRPORT";

export interface Zone {
  id: string;
  cityId: string;
  citySlug: string;
  code: string;
  name: string;
  zoneType: ZoneType;
  centerLatitude: string;
  centerLongitude: string;
  areaSqKm: string | null;
  population: number | null;
  roadCapacityVph: number | null;
  active: boolean;
  demoData: boolean;
}

// --- Platform ---------------------------------------------------------------

export interface PlatformInfo {
  name: string;
  version: string;
  demoMode: boolean;
  /** False when no mail provider is wired up; the UI must not claim delivery. */
  emailDeliveryEnabled: boolean;
}

// --- Live intelligence (PRD §9) ---------------------------------------------

/**
 * The four city-condition states. One scale for congestion, air quality and
 * composite risk alike, so a colour means the same thing wherever it appears.
 */
export type ConditionLevel = "NORMAL" | "MODERATE" | "HIGH" | "CRITICAL";

/**
 * Latest curated conditions for one zone.
 *
 * Every metric is nullable, and `hasData` distinguishes "measured as zero" from
 * "never measured". Rendering both as 0 would report a stopped feed as a quiet
 * street — the most dangerous direction for this dashboard to be wrong.
 */
export interface ZoneCondition {
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  zoneType: ZoneType;
  latitude: string;
  longitude: string;

  /** The curated window these values came from; null when the zone is silent. */
  windowStart: string | null;
  windowEnd: string | null;

  vehicleCount: number | null;
  averageSpeedKph: string | null;
  occupancyRatio: string | null;
  congestionLevel: ConditionLevel | null;

  aqi: number | null;
  aqiCategory: string | null;

  temperatureC: string | null;
  precipitationMmH: string | null;
  weatherCondition: string | null;

  activeIncidents: number;
  activeEvents: number;

  riskScore: string | null;
  riskLevel: ConditionLevel | null;

  /** Risk about an hour earlier, for a trend. Null when the zone has no window
      that far back — which is not the same as unchanged, and must not render as
      a flat arrow. */
  previousRiskScore: string | null;
  /** The window the comparison is against, so a caller can say what it compared
      to rather than implying an exact interval. */
  previousWindowStart: string | null;

  /** Raw events behind the window. A low count means a thin sample. */
  sampleCount: number;
  demoData: boolean;
  hasData: boolean;
}

export interface CityKpis {
  averageCongestion: string | null;
  averageSpeedKph: string | null;
  totalVehicleCount: number | null;
  averageAqi: number | null;
  temperatureC: string | null;
  precipitationMmH: string | null;
  weatherCondition: string | null;
  /** Null when no zone reported — summed from windows, so absent is not zero. */
  activeIncidents: number | null;
  activeEvents: number | null;
  /** Counted from the alerts table, which needs no recent window: zero is real. */
  activeAlerts: number;
  averageRiskScore: string | null;
  overallRiskLevel: ConditionLevel | null;
  /** Zones with a recent window, out of those monitored. */
  zonesReporting: number;
  zonesMonitored: number;
  zonesDegraded: number;
}

export interface CitySnapshot {
  cityId: string;
  citySlug: string;
  cityName: string;
  timezone: string;
  /** Newest curated window in the city; null when nothing has arrived. */
  asOf: string | null;
  dataAgeSeconds: number | null;
  /** True when the newest window is older than the freshness budget. */
  stale: boolean;
  kpis: CityKpis;
  zones: ZoneCondition[];
  demoData: boolean;
}

export interface ZoneHistoryPoint {
  windowStart: string;
  occupancyRatio: string | null;
  averageSpeedKph: string | null;
  aqi: number | null;
  riskScore: string | null;
  activeIncidents: number;
  sampleCount: number;
}

export interface CityHistoryPoint {
  windowStart: string;
  /** Every measurement is nullable — a window nobody reported in is absent from
      the series, and one where a signal was missing carries null for it. */
  averageCongestion: string | null;
  averageSpeedKph: string | null;
  averageAqi: number | null;
  averageRiskScore: string | null;
  totalVehicleCount: number | null;
  activeIncidents: number | null;
  /** Zones that contributed to this window, so a caller can read its coverage. */
  reportingZones: number;
}

export interface CityHistory {
  cityId: string;
  citySlug: string;
  from: string;
  to: string;
  windows: number;
  /** Width of each point. Wider than the curated window on long ranges, so the
      whole range is covered rather than its first 500 windows returned as if
      they were all of it. */
  bucketMinutes: number;
  zonesMonitored: number;
  points: CityHistoryPoint[];
}

export interface ZoneHistory {
  zoneId: string;
  zoneCode: string;
  from: string;
  to: string;
  windowCount: number;
  points: ZoneHistoryPoint[];
}

// --- Alerts (PRD §17) --------------------------------------------------------

export type AlertType =
  | "CRITICAL"
  | "WARNING"
  | "INFORMATIONAL"
  | "SYSTEM"
  | "DATA_QUALITY"
  | "SECURITY";

export type AlertSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type AlertStatus = "NEW" | "ACKNOWLEDGED" | "INVESTIGATING" | "RESOLVED";

/**
 * A raised alert together with the measurement that produced it.
 *
 * The provenance fields are not decoration: an alert a user cannot interrogate
 * has to be taken on faith, and PRD §15 requires the platform to cite the data
 * behind what it claims.
 */
export interface AlertDetail {
  id: string;
  alertType: AlertType;
  severity: AlertSeverity;
  status: AlertStatus;
  title: string;
  description: string;

  zoneId: string | null;
  zoneCode: string | null;
  zoneName: string | null;
  cityId: string | null;
  citySlug: string | null;

  ruleCode: string;
  metricName: string | null;
  observedValue: string | null;
  thresholdValue: string | null;
  windowStart: string | null;

  recommendedAction: string | null;

  raisedAt: string;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolutionNote: string | null;

  demoData: boolean;
}

export interface AlertSummary {
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  unacknowledged: number;
}

// --- Forecasting (PRD §11) ---------------------------------------------------

/** Horizons the platform trains a separate model for. */
export type ForecastHorizon = 15 | 30 | 60 | 180 | 360;

export type ForecastMetric =
  | "occupancy_ratio"
  | "average_speed_kph"
  | "vehicle_count"
  | "risk_score";

/** One feature's signed contribution to a prediction. */
export interface ForecastFactor {
  factor: string;
  feature: string;
  value: string;
  direction: "increases" | "decreases";
  effect: string;
}

export interface ForecastPoint {
  id: string;
  horizonMinutes: ForecastHorizon;
  targetTime: string;
  issuedAt: string;
  basedOnWindow: string;
  predictedValue: string;
  lowerBound: string | null;
  upperBound: string | null;
  /** Derived from the model's measured error at this metric and horizon. */
  confidence: string;
  /** Null for metrics with no severity scale, such as speed. */
  riskLevel: ConditionLevel | null;
  contributingFactors: ForecastFactor[];
  /** What the model actually achieved on held-out data. */
  measuredMae: string | null;
  /** Error of the naive no-change prediction, for comparison. */
  baselineMae: string | null;
  improvementOverBaseline: string | null;
}

export interface ModelSummary {
  id: string;
  name: string;
  version: string;
  algorithm: string;
  trainedFrom: string;
  trainedTo: string;
  evaluatedFrom: string;
  evaluatedTo: string;
  trainingRows: number;
  evaluationRows: number;
}

export interface ZoneForecast {
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  targetMetric: ForecastMetric;
  /** Null when the zone has no recent observation to compare against. */
  currentValue: string | null;
  horizons: ForecastPoint[];
  model: ModelSummary | null;
  demoData: boolean;
}

export interface ZoneOutlook {
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  latitude: string;
  longitude: string;
  predictedValue: string;
  confidence: string;
  riskLevel: ConditionLevel | null;
  targetTime: string;
}

export interface CityOutlook {
  cityId: string;
  citySlug: string;
  targetMetric: ForecastMetric;
  horizonMinutes: ForecastHorizon;
  targetTime: string | null;
  zonesForecast: number;
  zonesDegraded: number;
  zones: ZoneOutlook[];
  model: ModelSummary | null;
}

export interface AccuracyEntry {
  targetMetric: ForecastMetric;
  horizonMinutes: ForecastHorizon;
  scoredCount: number;
  productionMae: string;
  holdoutMae: string;
  withinIntervalPct: string;
}

export interface AccuracyReport {
  model: ModelSummary;
  entries: AccuracyEntry[];
}

// --- What-If Simulator (PRD §14) --------------------------------------------

/**
 * Whether the scenario named this zone or the engine inferred the effect.
 * An inferred spillover deserves less confidence than a stated closure.
 */
export type ImpactSource = "DIRECT" | "SPILLOVER" | "CITYWIDE";

export interface ScenarioWeather {
  rainIntensityMmH?: number;
  temperatureC?: number;
  windSpeedKph?: number;
}

export interface ScenarioEvent {
  zoneCode: string;
  eventType: string;
  expectedAttendance: number;
  startsInHours: number;
  durationHours: number;
}

export interface ScenarioInfrastructure {
  closedRoadZoneCodes?: string[];
  capacityReductionPct?: number;
  transitDisruptionPct?: number;
}

export interface ScenarioTraffic {
  volumeChangePct?: number;
  zoneCodes?: string[];
}

export interface RunScenarioRequest {
  name: string;
  description?: string;
  citySlug: string;
  weather?: ScenarioWeather;
  event?: ScenarioEvent;
  infrastructure?: ScenarioInfrastructure;
  traffic?: ScenarioTraffic;
}

export interface SimulationRecommendation {
  action: string;
  reason: string;
  zoneCode: string | null;
  priority: "HIGH" | "MEDIUM" | "LOW";
}

export interface ZoneImpact {
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  latitude: string;
  longitude: string;
  baselineOccupancy: string | null;
  simulatedOccupancy: string | null;
  baselineSpeedKph: string | null;
  simulatedSpeedKph: string | null;
  baselineRiskScore: string | null;
  simulatedRiskScore: string | null;
  baselineCongestion: ConditionLevel | null;
  simulatedCongestion: ConditionLevel | null;
  delayChangeMin: string | null;
  parkingChangePct: string | null;
  crowdChangePct: string | null;
  impactSource: ImpactSource;
}

export interface SimulationDetail {
  id: string;
  name: string;
  description: string | null;
  citySlug: string;
  createdAt: string;
  /** The curated window the counterfactual departed from. */
  baselineWindow: string;
  /** Which set of engine assumptions produced this. */
  engineVersion: string;
  computedMs: number | null;
  trafficChangePct: string | null;
  crowdChangePct: string | null;
  parkingChangePct: string | null;
  delayChangeMin: string | null;
  baselineRisk: string | null;
  simulatedRisk: string | null;
  zonesAffected: number;
  zones: ZoneImpact[];
  recommendations: SimulationRecommendation[];
  /** True when the baseline was synthetic — so the conclusions are too. */
  demoData: boolean;
}

export interface SimulationSummary {
  id: string;
  name: string;
  description: string | null;
  createdAt: string;
  baselineWindow: string;
  engineVersion: string;
  trafficChangePct: string | null;
  delayChangeMin: string | null;
  baselineRisk: string | null;
  simulatedRisk: string | null;
  zonesAffected: number;
  demoData: boolean;
}

// --- Intelligence (PRD §12, §13, §16) ---------------------------------------

export type AnomalyType = "SPIKE" | "DROP" | "SUSTAINED_SHIFT";

/**
 * A departure from what a zone normally does at this hour.
 *
 * Distinct from an Alert, which fires on a fixed threshold: 8,000 vehicles is
 * unremarkable on a Tuesday morning and an anomaly at 3 a.m.
 */
export interface AnomalyDetail {
  id: string;
  zoneId: string;
  zoneCode: string;
  zoneName: string;
  metric: string;
  anomalyType: AnomalyType;
  severity: AlertSeverity;
  windowStart: string;
  observedValue: string;
  /** What this zone normally does at this hour of the week. */
  baselineValue: string;
  deviationScore: string;
  percentChange: string | null;
  /** Historical windows the baseline rests on. */
  baselineSamples: number;
  explanation: string;
  detectedAt: string;
  demoData: boolean;
}

export interface Correlation {
  conditionA: string;
  conditionB: string;
  statement: string;
  /** P(B|A)/P(B). Above 1 means A raises the odds of B. */
  lift: string;
  confidence: string;
  windowsWithA: number;
  windowsWithBoth: number;
  windowsTotal: number;
  /** Always false — measured co-occurrence, never a causal claim. */
  impliesCausation: boolean;
}

export interface RecalledSituation {
  zoneCode: string;
  zoneName: string;
  occurredAt: string;
  occupancyAtStart: string | null;
  peakOccupancy: string | null;
  occupancyChangePct: string | null;
  speedChangePct: string | null;
  riskChangePct: string | null;
  outcomeHorizonMinutes: number;
}

export interface MemoryRecall {
  rainBand: string;
  dayType: string;
  hourBand: string;
  hadEvent: boolean;
  incidentBand: string;
  /** False when too few comparable situations exist to say anything. */
  sufficientData: boolean;
  insufficientReason: string | null;
  /** True when the exact fingerprint was too rare and the match was widened. */
  relaxedMatch: boolean;
  matchCount: number;
  medianOccupancyChangePct: string | null;
  medianSpeedChangePct: string | null;
  medianRiskChangePct: string | null;
  summary: string;
  examples: RecalledSituation[];
}

export interface InsightsSummary {
  citySlug: string;
  anomaliesLast24h: number;
  topAnomalies: AnomalyDetail[];
  correlations: Correlation[];
  currentSituation: MemoryRecall | null;
  baselineBuckets: number;
}

export interface DataSourceSummary {
  id: string;
  code: string;
  name: string;
  description: string | null;
  sourceType: string;
  ingestionMode: string;
  status: string;
  /** Null means never delivered — a different problem from delivered long ago. */
  lastIngestedAt: string | null;
  secondsSinceLastIngest: number | null;
  /** Counted from the event tables, not read from lastIngestedAt. */
  rowsInWindow: number;
  /** ACTIVE but delivering nothing: configured to run and not running. */
  silent: boolean;
  demoData: boolean;
}

export interface DataSourceList {
  windowHours: number;
  total: number;
  active: number;
  silent: number;
  sources: DataSourceSummary[];
}

export interface StageQuality {
  stage: string;
  windows: number;
  recordsReceived: number;
  recordsValid: number;
  recordsRejected: number;
  recordsDuplicate: number;
  recordsLate: number;
  /** Null when the stage received nothing — undefined, not a ratio of zero. */
  validityRatio: string | null;
  /** Null when the pipeline recorded no lag — absent, not zero. */
  maxLagSeconds: number | null;
  newestWindowEnd: string | null;
}

export interface PipelineHealth {
  windowHours: number;
  /** Only instrumented stages. A missing stage is unmeasured, not idle. */
  stages: StageQuality[];
  deadLettered: number;
  silentSources: number;
  totalSources: number;
}

export interface ApiKeySummary {
  id: string;
  name: string;
  description: string | null;
  /** Identifies a key without revealing it. Not a credential. */
  keyPrefix: string;
  scopes: string[];
  owner: string;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  revokedReason: string | null;
  /** Null when never used — different from used long ago. */
  lastUsedAt: string | null;
  active: boolean;
  inactiveReason: string | null;
}

export interface ApiKeyCreated {
  key: ApiKeySummary;
  /** The only time this is returned. Nothing stores it. */
  secret: string;
}

export interface ScopeCatalogue {
  /** Exactly the permissions the caller holds and may therefore grant. */
  grantable: string[];
}
