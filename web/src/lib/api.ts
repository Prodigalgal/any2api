export type ProviderModel = {
  id: string;
  owned_by: string;
  provider_name: string;
  available: boolean;
  cataloged: boolean;
  capabilities: Record<string, unknown>;
  supported_parameters: Record<string, string[]>;
  provider_options: Record<string, string>;
  max_context_tokens: number | null;
  max_input_tokens: number | null;
  max_output_tokens: number | null;
  reasoning: { supported: boolean; levels: string[] };
  tools: { supported: boolean; types: string[]; parallel: boolean };
  streaming: boolean;
  multimodal: { input: string[]; output: string[] };
  runtime: {
    status: "READY" | "DEGRADED" | "UNAVAILABLE";
    eligible_account_count: number;
    available_account_count: number;
    quota_limited_account_count: number;
    rolling_request_count: number;
    rolling_attempt_count: number;
    rolling_success_rate: number;
    p50_ms: number;
    p95_ms: number;
    last_attempt_at: string | null;
    last_success_at: string | null;
    probe_status: string | null;
    probe_error: string | null;
    probed_at: string | null;
    concurrent: number;
    queue_depth: number;
    circuit_state: string;
  };
};

export type ModelsResponse = {
  object: "list";
  data: ProviderModel[];
};

export type ProviderDescriptor = {
  id: string;
  displayName: string;
  adapterVersion: string;
  requestSchemaVersion: string;
  defaultModels: string[];
  capabilities: Record<string, string>;
  lifecycleOperations: string[];
  configured: boolean;
};

export type ProvidersResponse = {
  object: "list";
  automationCatalogReady: boolean;
  data: ProviderDescriptor[];
};

export type ProviderRuntime = {
  id: string;
  displayName: string;
  adapterVersion: string;
  defaultModels: string[];
  capabilities: Record<string, string>;
  installed: boolean;
  enabled: boolean;
  accountCount: number;
  enabledAccountCount: number;
  modelCount: number;
};

export type ProviderOption = [id: string, label: string];

export function providerOptions(
  catalog?: ProvidersResponse,
  filter?: { capability?: string; lifecycleOperation?: string },
): ProviderOption[] {
  return (catalog?.data ?? [])
    .filter((provider) => !filter?.capability || (
      provider.capabilities[filter.capability] !== undefined
      && provider.capabilities[filter.capability] !== "UNSUPPORTED"
    ))
    .filter((provider) => !filter?.lifecycleOperation
      || provider.lifecycleOperations.includes(filter.lifecycleOperation))
    .map((provider) => [provider.id, provider.displayName]);
}

export type HealthResponse = {
  status: string;
  components?: Record<string, { status: string }>;
};

export type Account = {
  id: string;
  providerId: string;
  externalId: string;
  email: string | null;
  status: string;
  enabled: boolean;
  maxConcurrency: number;
  priority: number;
  weight: number;
  expiresAt: string | null;
  requestCount: number;
  successCount: number;
  failureCount: number;
  lastError: string | null;
  updatedAt?: string;
};

export type AccountDetail = {
  account: Account & {
    cooldownUntil: string | null;
    metadata: Record<string, unknown>;
    version: number;
    lastUsedAt: string | null;
    lastSuccessAt: string | null;
    lastFailureAt: string | null;
    createdAt: string;
    updatedAt: string;
  };
  credential: {
    configured: boolean;
    type: string | null;
    version: number;
    expiresAt: string | null;
    updatedAt: string | null;
  };
};

export type AccountProbeResult = {
  ready: boolean;
  model: string;
  errorClass: string;
  output: string;
  durationMs: number;
  completedAt: string;
  account: Account;
};

export type TokenLimits = {
  maxContextTokens: number | null;
  maxInputTokens: number | null;
  maxOutputTokens: number | null;
};

export type ModelLimitPolicy = {
  providerId: string;
  providerName: string;
  modelId: string;
  displayName: string;
  catalogSource: string;
  discovered: TokenLimits;
  overrides: TokenLimits;
  effective: TokenLimits;
};

export type AccountExpiryFilter = "ANY" | "VALID" | "EXPIRING_SOON" | "EXPIRED" | "NEVER";

export type AccountPageQuery = {
  provider?: string;
  status?: string;
  enabled?: boolean;
  query?: string;
  expiry?: AccountExpiryFilter;
  page: number;
  size: number;
};

export type AccountPage = {
  items: Account[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
};

export type AccountCommand = {
  name: string;
  displayName: string;
  idempotent: boolean;
};

export type LoginChallenge = {
  challengeToken: string;
  expression: string;
  difficulty: number;
  expiresAt: string;
};

export type AdminSession = {
  authenticated: boolean;
  username?: string;
};

export type RegistrationJob = {
  id: string;
  providerId: string;
  status: string;
  target: number;
  maxAttempts: number;
  concurrency: number;
  attemptIntervalSeconds: number;
  roundIntervalSeconds: number;
  attemptTimeoutSeconds: number;
  flowMaxAttempts: number;
  maxConsecutiveFailureBatches: number;
  proxyPolicy: RegistrationProxyPolicy;
  headless: boolean;
  mailDomain: string;
  aiCaptchaEnabled: boolean;
  aiCaptchaMode: CaptchaAiMode;
  attempts: number;
  successCount: number;
  failureCount: number;
  cancelRequested: boolean;
  lastErrorClass: string | null;
  lastErrorCode: string | null;
  lastErrorStage: string | null;
  lastErrorDetail: string | null;
  lastErrorCorrelationId: string | null;
  result: { account_ids?: string[] } | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
};

export type RegistrationJobPage = {
  items: RegistrationJob[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
};

export type RegistrationScheduleType = "ONCE" | "INTERVAL";

export type RegistrationSchedule = {
  id: string;
  name: string;
  providerId: string;
  scheduleType: RegistrationScheduleType;
  intervalMinutes: number | null;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastJobId: string | null;
  job: Omit<RegistrationJobFormValue, "idempotencyKey"> & { idempotencyKey?: string | null };
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
};

export type RegistrationSchedulePage = {
  items: RegistrationSchedule[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
};

export type RegistrationJobFormValue = {
  providerId: string;
  target: number;
  maxAttempts: number;
  concurrency: number;
  attemptIntervalSeconds: number;
  roundIntervalSeconds: number;
  attemptTimeoutSeconds: number;
  flowMaxAttempts: number;
  maxConsecutiveFailureBatches: number;
  proxyPolicy: RegistrationProxyPolicy;
  headless: boolean;
  mailDomain: string | null;
  aiCaptchaEnabled: boolean;
  aiCaptchaMode: CaptchaAiMode;
  idempotencyKey?: string | null;
};

export type CaptchaAiMode = "AUTO" | "INTERNAL" | "EXTERNAL";
export type RegistrationProxyPolicy = "PROVIDER_DEFAULT" | "DIRECT" | "REQUIRED_POOL";

export type OperationEvent = {
  id: string;
  correlationId: string;
  domain: "REGISTRATION" | "LIFECYCLE" | "INFERENCE";
  providerId: string;
  operation: string;
  aggregateType: string;
  aggregateId: string;
  accountId: string | null;
  attempt: number;
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
  stage: string;
  errorCode: string | null;
  errorDetail: string | null;
  durationMs: number;
  startedAt: string;
  finishedAt: string | null;
};

export type UsageEvent = {
  requestId: string;
  apiKeyId: string | null;
  providerId: string;
  accountId: string | null;
  modelId: string;
  protocol: string;
  success: boolean;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  durationMs: number;
  errorClass: string | null;
  attempt: number;
  requestKind: "INFERENCE" | "PROBE";
  usageSource: "UPSTREAM" | "ESTIMATED";
  queueMs: number;
  accountAcquireMs: number;
  ttfbMs: number;
  generationMs: number;
  createdAt: string;
};

export type RequestLogPage = {
  items: UsageEvent[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
};

export type RequestLogDetail = {
  request: UsageEvent;
  input: unknown;
  output: unknown;
};

export type OperationLogPage = {
  items: OperationEvent[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
};

export type ProxyPool = {
  id: string;
  name: string;
  mode: "SUBSCRIPTION_URL" | "NODE_LIST";
  enabled: boolean;
  nodeCount: number;
  sourceConfigured: boolean;
  providerIds: string[];
  bindingScopes: Record<string, ProxyTrafficScope[]>;
  createdAt: string;
  updatedAt: string;
};

export type ProxyTrafficScope = "REGISTRATION" | "LIFECYCLE" | "INFERENCE";

export type ApiKeyProtocol = "CHAT_COMPLETIONS" | "RESPONSES" | "IMAGES";
export type ApiKeyFeature = "MULTIMODAL_INPUT" | "FILE_UPLOADS" | "TOOL_CALLING";

export type DistributionApiKey = {
  id: string;
  name: string;
  prefix: string;
  enabled: boolean;
  providerModels: Record<string, string[]>;
  protocols: ApiKeyProtocol[];
  features: ApiKeyFeature[];
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type CreatedDistributionApiKey = {
  key: DistributionApiKey;
  secret: string;
};

export type ApiKeyUsageWindow = {
  requestCount: number;
  attemptCount: number;
  successCount: number;
  failureCount: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  p50DurationMs: number;
  p95DurationMs: number;
  lastUsedAt: string | null;
};

export type ApiKeyDetail = {
  key: DistributionApiKey;
  lifetime: ApiKeyUsageWindow;
  last24Hours: ApiKeyUsageWindow;
  last7Days: ApiKeyUsageWindow;
  last30Days: ApiKeyUsageWindow;
  modelUsage: Array<{
    providerId: string;
    modelId: string;
    requestCount: number;
    successCount: number;
    inputTokens: number;
    outputTokens: number;
    p95DurationMs: number;
    lastUsedAt: string | null;
  }>;
};

export type TempMailSettings = {
  apiBase: string;
  adminPassword: string;
  sitePassword: string;
  domains: string[];
  pollSeconds: number;
  messageTimeoutSeconds: number;
  requestTimeoutSeconds: number;
};

export type RegistrationDefaults = {
  target: number;
  maxAttempts: number;
  concurrency: number;
  attemptIntervalSeconds: number;
  roundIntervalSeconds: number;
  attemptTimeoutSeconds: number;
  flowMaxAttempts: number;
  maxConsecutiveFailureBatches: number;
  proxyPolicy: RegistrationProxyPolicy;
  headless: boolean;
  aiCaptchaEnabled: boolean;
  aiCaptchaMode: CaptchaAiMode;
};

export type SystemSettings = {
  tempMail: TempMailSettings;
  registrationDefaults: RegistrationDefaults;
  providerKeepalive: ProviderKeepaliveSettings;
};

export type ProviderKeepalivePolicy = {
  intervalMinutes: number;
  jitterMinutes: number;
  parameters: Record<string, unknown>;
};

export type ProviderKeepaliveSettings = {
  providers: Record<string, ProviderKeepalivePolicy>;
};

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(5_000) });
  if (!response.ok) throw new Error(`${url} 返回 HTTP ${response.status}`);
  return response.json() as Promise<T>;
}

async function adminJson<T>(
  url: string,
  init?: RequestInit,
  redirectOnUnauthorized = true,
): Promise<T> {
  const response = await fetch(url, {
    ...init,
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });
  if (!response.ok) {
    if (
      response.status === 401
      && redirectOnUnauthorized
      && typeof window !== "undefined"
      && window.location.pathname !== "/login"
    ) {
      window.location.replace("/login");
    }
    if (response.status === 401 && !redirectOnUnauthorized) {
      throw new Error("登录验证失败，请检查管理员密码和当前数学验证码");
    }
    const payload = await response.json().catch(() => null) as { error?: { message?: string } } | null;
    throw new Error(payload?.error?.message ?? `${url} 返回 HTTP ${response.status}`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  models: () => getJson<ModelsResponse>("/api/catalog/v1/models"),
  providers: () => getJson<ProvidersResponse>("/api/catalog/v1/providers"),
  health: () => getJson<HealthResponse>("/actuator/health"),
  loginChallenge: () => getJson<LoginChallenge>("/api/admin/v1/login-challenge"),
  login: (body: Record<string, unknown>) => adminJson<{ authenticated: boolean; username: string }>(
    "/api/admin/v1/session", { method: "POST", body: JSON.stringify(body) }, false,
  ),
  session: () => adminJson<AdminSession>(
    "/api/admin/v1/session", undefined, false,
  ),
  logout: () => adminJson<{ authenticated: boolean }>(
    "/api/admin/v1/session", { method: "DELETE" },
  ),
  accountPage: (query: AccountPageQuery) => {
    const params = new URLSearchParams({
      page: String(query.page),
      size: String(query.size),
      expiry: query.expiry ?? "ANY",
    });
    if (query.provider) params.set("provider", query.provider);
    if (query.status) params.set("status", query.status);
    if (query.enabled !== undefined) params.set("enabled", String(query.enabled));
    if (query.query) params.set("query", query.query);
    return adminJson<AccountPage>(`/api/admin/v1/accounts/page?${params.toString()}`);
  },
  adminProviders: () => adminJson<ProviderRuntime[]>("/api/admin/v1/providers"),
  updateProvider: (id: string, enabled: boolean) => adminJson<ProviderRuntime>(
    `/api/admin/v1/providers/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify({ enabled }) },
  ),
  importAccount: (body: Record<string, unknown>) => adminJson(
    "/api/admin/v1/accounts/import", { method: "POST", body: JSON.stringify(body) },
  ),
  updateAccount: (id: string, body: Record<string, unknown>) => adminJson<Account>(
    `/api/admin/v1/accounts/${id}`, { method: "PATCH", body: JSON.stringify(body) },
  ),
  accountDetail: (id: string) => adminJson<AccountDetail>(
    `/api/admin/v1/accounts/${id}`,
  ),
  probeAccount: (id: string, modelId?: string) => modelId
    ? adminJson<AccountProbeResult>(
      "/api/admin/v1/account-probes",
      {
        method: "POST",
        body: JSON.stringify({ accountId: id, modelId }),
        signal: AbortSignal.timeout(300_000),
      },
    )
    : adminJson<AccountProbeResult>(
      `/api/admin/v1/accounts/${id}/probe`,
      { method: "POST", signal: AbortSignal.timeout(300_000) },
    ),
  deleteAccount: (id: string) => adminJson<void>(
    `/api/admin/v1/accounts/${id}`, { method: "DELETE" },
  ),
  reauthenticateAccount: (id: string) => adminJson<Account>(
    `/api/admin/v1/accounts/${id}/reauthenticate`, { method: "POST" },
  ),
  accountCommands: (id: string) => adminJson<AccountCommand[]>(
    `/api/admin/v1/accounts/${id}/commands`,
  ),
  accountEvents: (id: string) => adminJson<OperationEvent[]>(
    `/api/admin/v1/accounts/${id}/events`,
  ),
  executeAccountCommand: (id: string, command: string) => adminJson<{ account: Account }>(
    `/api/admin/v1/accounts/${id}/commands/${encodeURIComponent(command)}`, { method: "POST" },
  ),
  registrationJobPage: (query: {
    provider?: string; status?: string; page: number; size: number;
  }) => {
    const params = new URLSearchParams({ page: String(query.page), size: String(query.size) });
    if (query.provider) params.set("provider", query.provider);
    if (query.status) params.set("status", query.status);
    return adminJson<RegistrationJobPage>(
      `/api/admin/v1/registration-jobs/page?${params.toString()}`,
    );
  },
  createRegistrationJob: (body: Record<string, unknown>) => adminJson<RegistrationJob>(
    "/api/admin/v1/registration-jobs", { method: "POST", body: JSON.stringify(body) },
  ),
  cancelRegistrationJob: (id: string) => adminJson<RegistrationJob>(
    `/api/admin/v1/registration-jobs/${id}/cancel`, { method: "POST" },
  ),
  registrationJobEvents: (id: string) => adminJson<OperationEvent[]>(
    `/api/admin/v1/registration-jobs/${id}/events`,
  ),
  registrationSchedulePage: (query: {
    provider?: string; enabled?: boolean; page: number; size: number;
  }) => {
    const params = new URLSearchParams({ page: String(query.page), size: String(query.size) });
    if (query.provider) params.set("provider", query.provider);
    if (query.enabled !== undefined) params.set("enabled", String(query.enabled));
    return adminJson<RegistrationSchedulePage>(
      `/api/admin/v1/registration-schedules/page?${params.toString()}`,
    );
  },
  createRegistrationSchedule: (body: Record<string, unknown>) => adminJson<RegistrationSchedule>(
    "/api/admin/v1/registration-schedules", { method: "POST", body: JSON.stringify(body) },
  ),
  updateRegistrationSchedule: (id: string, body: Record<string, unknown>) => adminJson<RegistrationSchedule>(
    `/api/admin/v1/registration-schedules/${id}`,
    { method: "PUT", body: JSON.stringify(body) },
  ),
  setRegistrationScheduleEnabled: (id: string, enabled: boolean) => adminJson<RegistrationSchedule>(
    `/api/admin/v1/registration-schedules/${id}/enabled`,
    { method: "PATCH", body: JSON.stringify({ enabled }) },
  ),
  deleteRegistrationSchedule: (id: string) => adminJson<void>(
    `/api/admin/v1/registration-schedules/${id}`, { method: "DELETE" },
  ),
  requestLogs: (query: Record<string, string | number>) => {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => {
      if (value !== "") params.set(key, String(value));
    });
    return adminJson<RequestLogPage>(`/api/admin/v1/requests?${params.toString()}`);
  },
  requestLogDetail: (requestId: string, attempt: number) => adminJson<RequestLogDetail>(
    `/api/admin/v1/requests/${encodeURIComponent(requestId)}/attempts/${attempt}`,
  ),
  operationLogs: (query: Record<string, string | number>) => {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => {
      if (value !== "") params.set(key, String(value));
    });
    return adminJson<OperationLogPage>(`/api/admin/v1/operations?${params.toString()}`);
  },
  proxyPools: () => adminJson<ProxyPool[]>("/api/admin/v1/proxy-pools"),
  createProxyPool: (body: Record<string, unknown>) => adminJson<ProxyPool>(
    "/api/admin/v1/proxy-pools", { method: "POST", body: JSON.stringify(body) },
  ),
  updateProxyPool: (id: string, body: Record<string, unknown>) => adminJson<ProxyPool>(
    `/api/admin/v1/proxy-pools/${id}`, { method: "PUT", body: JSON.stringify(body) },
  ),
  deleteProxyPool: (id: string) => adminJson<void>(
    `/api/admin/v1/proxy-pools/${id}`, { method: "DELETE" },
  ),
  apiKeys: () => adminJson<DistributionApiKey[]>("/api/admin/v1/api-keys"),
  apiKeyDetail: (id: string) => adminJson<ApiKeyDetail>(`/api/admin/v1/api-keys/${id}`),
  createApiKey: (body: Record<string, unknown>) => adminJson<CreatedDistributionApiKey>(
    "/api/admin/v1/api-keys", { method: "POST", body: JSON.stringify(body) },
  ),
  updateApiKey: (id: string, enabled: boolean) => adminJson<DistributionApiKey>(
    `/api/admin/v1/api-keys/${id}`,
    { method: "PATCH", body: JSON.stringify({ enabled }) },
  ),
  deleteApiKey: (id: string) => adminJson<void>(
    `/api/admin/v1/api-keys/${id}`, { method: "DELETE" },
  ),
  systemSettings: () => adminJson<SystemSettings>("/api/admin/v1/settings"),
  updateTempMailSettings: (body: TempMailSettings) => adminJson<TempMailSettings>(
    "/api/admin/v1/settings/temp-mail", { method: "PUT", body: JSON.stringify(body) },
  ),
  updateRegistrationDefaults: (body: RegistrationDefaults) => adminJson<RegistrationDefaults>(
    "/api/admin/v1/settings/registration-defaults",
    { method: "PUT", body: JSON.stringify(body) },
  ),
  modelLimitPolicies: () => adminJson<ModelLimitPolicy[]>(
    "/api/admin/v1/models/limits",
  ),
  updateModelLimitPolicy: (body: {
    providerId: string;
    modelId: string;
    maxContextTokens: number | null;
    maxInputTokens: number | null;
    maxOutputTokens: number | null;
  }) => adminJson<ModelLimitPolicy>(
    "/api/admin/v1/models/limits",
    { method: "PUT", body: JSON.stringify(body) },
  ),
  updateProviderKeepalive: (body: ProviderKeepaliveSettings) =>
    adminJson<ProviderKeepaliveSettings>(
      "/api/admin/v1/settings/provider-keepalive",
      { method: "PUT", body: JSON.stringify(body) },
    ),
};
