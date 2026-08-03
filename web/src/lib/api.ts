export type ProviderModel = {
  id: string;
  owned_by: string;
  provider_name: string;
  available: boolean;
  capabilities: Record<string, string>;
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
  attempts: number;
  successCount: number;
  failureCount: number;
  cancelRequested: boolean;
  lastErrorClass: string | null;
  result: { account_ids?: string[] } | null;
  createdAt: string;
  updatedAt: string;
  finishedAt: string | null;
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

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(5_000) });
  if (!response.ok) throw new Error(`${url} returned ${response.status}`);
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
    throw new Error(payload?.error?.message ?? `${url} returned ${response.status}`);
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
  deleteAccount: (id: string) => adminJson<void>(
    `/api/admin/v1/accounts/${id}`, { method: "DELETE" },
  ),
  reauthenticateAccount: (id: string) => adminJson<Account>(
    `/api/admin/v1/accounts/${id}/reauthenticate`, { method: "POST" },
  ),
  accountCommands: (id: string) => adminJson<AccountCommand[]>(
    `/api/admin/v1/accounts/${id}/commands`,
  ),
  executeAccountCommand: (id: string, command: string) => adminJson<{ account: Account }>(
    `/api/admin/v1/accounts/${id}/commands/${encodeURIComponent(command)}`, { method: "POST" },
  ),
  registrationJobs: (provider?: string) => adminJson<RegistrationJob[]>(
    `/api/admin/v1/registration-jobs${provider ? `?provider=${encodeURIComponent(provider)}` : ""}`,
  ),
  createRegistrationJob: (body: Record<string, unknown>) => adminJson<RegistrationJob>(
    "/api/admin/v1/registration-jobs", { method: "POST", body: JSON.stringify(body) },
  ),
  cancelRegistrationJob: (id: string) => adminJson<RegistrationJob>(
    `/api/admin/v1/registration-jobs/${id}/cancel`, { method: "POST" },
  ),
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
};
