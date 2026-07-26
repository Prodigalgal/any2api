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

export type HealthResponse = {
  status: string;
  components?: Record<string, { status: string }>;
};

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { cache: "no-store", signal: AbortSignal.timeout(5_000) });
  if (!response.ok) throw new Error(`${url} returned ${response.status}`);
  return response.json() as Promise<T>;
}

export const api = {
  models: () => getJson<ModelsResponse>("/api/catalog/v1/models"),
  health: () => getJson<HealthResponse>("/actuator/health")
};
