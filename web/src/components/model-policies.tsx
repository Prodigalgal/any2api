"use client";

import {
  EditOutlined,
  FilterAltOffOutlined,
  RefreshOutlined,
  RestartAltOutlined,
  SaveOutlined,
  SearchOutlined,
  TuneOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  LinearProgress,
  MenuItem,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  api,
  providerOptions,
  type ModelLimitPolicy,
  type ProviderKeepalivePolicy,
  type ProviderKeepaliveSettings,
  type TokenLimits,
} from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

const defaultKeepalive: ProviderKeepalivePolicy = {
  intervalMinutes: 360,
  jitterMinutes: 20,
  parameters: {},
};

export function ModelPolicies() {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const [provider, setProvider] = useState("");
  const [search, setSearch] = useState("");
  const [editingModel, setEditingModel] = useState<ModelLimitPolicy | null>(null);
  const [editingKeepalive, setEditingKeepalive] = useState<string | null>(null);
  const policies = useQuery({ queryKey: ["model-limit-policies"], queryFn: api.modelLimitPolicies });
  const settings = useQuery({ queryKey: ["system-settings"], queryFn: api.systemSettings });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providers = providerOptions(catalog.data);
  const filteredModels = useMemo(() => {
    const term = search.trim().toLocaleLowerCase();
    return (policies.data ?? []).filter((model) => (
      (!provider || model.providerId === provider)
      && (!term
        || model.modelId.toLocaleLowerCase().includes(term)
        || model.displayName?.toLocaleLowerCase().includes(term))
    ));
  }, [policies.data, provider, search]);
  const error = policies.error ?? settings.error ?? catalog.error;

  const refresh = () => void Promise.all([
    policies.refetch(), settings.refetch(), catalog.refetch(),
  ]);

  return (
    <PageContainer>
      <PageHeader
        title="模型策略"
        description="模型预算与厂商账号保活治理"
        actions={
          <Tooltip title="刷新策略">
            <span>
              <IconButton
                aria-label="刷新策略"
                disabled={policies.isFetching || settings.isFetching}
                onClick={refresh}
                sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
              >
                <RefreshOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
        }
      />

      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}

      <DataSurface>
        <LinearProgress sx={{ visibility: policies.isFetching || settings.isFetching ? "visible" : "hidden" }} />
        <Tabs value={tab} onChange={(_, value: number) => setTab(value)} sx={{ px: 1.5, borderBottom: 1, borderColor: "divider" }}>
          <Tab label="模型限制" />
          <Tab label="保活策略" />
        </Tabs>

        {tab === 0 ? (
          <Box>
            <Box sx={{ p: { xs: 1.25, sm: 1.5 }, borderBottom: 1, borderColor: "divider", bgcolor: "#fbfcfc" }}>
              <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "minmax(240px, 1fr) 220px 40px" }, gap: 1.25 }}>
                <TextField
                  value={search}
                  placeholder="搜索模型"
                  onChange={(event) => setSearch(event.target.value)}
                  slotProps={{
                    input: {
                      startAdornment: <InputAdornment position="start"><SearchOutlined sx={{ fontSize: 18 }} /></InputAdornment>,
                      inputProps: { "aria-label": "搜索模型" },
                    },
                  }}
                />
                <TextField select label="厂商" value={provider} onChange={(event) => setProvider(event.target.value)}>
                  <MenuItem value="">全部厂商</MenuItem>
                  {providers.map(([id, label]) => <MenuItem key={id} value={id}>{label}</MenuItem>)}
                </TextField>
                <Tooltip title="清空筛选">
                  <span>
                    <IconButton
                      aria-label="清空筛选"
                      disabled={!provider && !search}
                      onClick={() => { setProvider(""); setSearch(""); }}
                    >
                      <FilterAltOffOutlined sx={{ fontSize: 18 }} />
                    </IconButton>
                  </span>
                </Tooltip>
              </Box>
            </Box>
            <Box sx={{ px: 2, height: 44, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
              <Typography sx={{ fontSize: 12.5, fontWeight: 720 }}>模型限制</Typography>
              <Typography color="text.secondary" sx={{ ml: 1, fontSize: 11.5 }}>{filteredModels.length} 个模型</Typography>
            </Box>
            <TableContainer sx={{ maxHeight: "calc(100vh - 320px)", minHeight: 360 }}>
              <Table stickyHeader size="small" sx={{ minWidth: 1180 }}>
                <TableHead>
                  <TableRow>
                    <TableCell rowSpan={2} sx={{ width: 120 }}>厂商</TableCell>
                    <TableCell rowSpan={2} sx={{ minWidth: 220 }}>模型</TableCell>
                    <TableCell align="center" colSpan={3}>厂商发现值</TableCell>
                    <TableCell align="center" colSpan={3}>当前生效值</TableCell>
                    <TableCell rowSpan={2} sx={{ width: 110 }}>来源</TableCell>
                    <TableCell rowSpan={2} align="right" sx={{ width: 64 }}>操作</TableCell>
                  </TableRow>
                  <TableRow>
                    <LimitHeaders />
                    <LimitHeaders />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {filteredModels.map((model) => (
                    <TableRow key={`${model.providerId}/${model.modelId}`} hover>
                      <TableCell>
                        <Typography sx={{ fontWeight: 700, fontSize: 12.5 }}>{model.providerName}</Typography>
                        <Typography color="text.secondary" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 10.5 }}>{model.providerId}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography noWrap title={model.modelId} sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>{model.modelId}</Typography>
                        {model.displayName && model.displayName !== model.modelId ? (
                          <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>{model.displayName}</Typography>
                        ) : null}
                      </TableCell>
                      <LimitCells limits={model.discovered} />
                      <LimitCells limits={model.effective} emphasized={hasOverrides(model.overrides)} />
                      <TableCell>
                        <Chip
                          size="small"
                          variant="outlined"
                          color={hasOverrides(model.overrides) ? "primary" : "default"}
                          label={hasOverrides(model.overrides) ? "管理员" : model.catalogSource}
                        />
                      </TableCell>
                      <TableCell align="right">
                        <Tooltip title="编辑模型限制">
                          <IconButton aria-label={`编辑 ${model.modelId} 限制`} onClick={() => setEditingModel(model)}>
                            <EditOutlined sx={{ fontSize: 17 }} />
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                  {!policies.isLoading && filteredModels.length === 0 ? (
                    <TableRow><TableCell colSpan={10} align="center" sx={{ height: 220, color: "text.secondary" }}>没有符合条件的模型</TableCell></TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        ) : (
          <KeepaliveTable
            providers={providers}
            settings={settings.data?.providerKeepalive ?? { providers: {} }}
            onEdit={setEditingKeepalive}
          />
        )}
      </DataSurface>

      {editingModel ? (
        <ModelLimitDialog
          policy={editingModel}
          onClose={() => setEditingModel(null)}
          onSaved={async () => {
            setEditingModel(null);
            await Promise.all([
              queryClient.invalidateQueries({ queryKey: ["model-limit-policies"] }),
              queryClient.invalidateQueries({ queryKey: ["models"] }),
            ]);
          }}
        />
      ) : null}
      {editingKeepalive ? (
        <KeepaliveDialog
          providerId={editingKeepalive}
          providerName={providers.find(([id]) => id === editingKeepalive)?.[1] ?? editingKeepalive}
          value={settings.data?.providerKeepalive.providers[editingKeepalive] ?? defaultKeepalive}
          settings={settings.data?.providerKeepalive ?? { providers: {} }}
          onClose={() => setEditingKeepalive(null)}
          onSaved={async () => {
            setEditingKeepalive(null);
            await queryClient.invalidateQueries({ queryKey: ["system-settings"] });
          }}
        />
      ) : null}
    </PageContainer>
  );
}

function LimitHeaders() {
  return <><TableCell align="right">上下文</TableCell><TableCell align="right">输入</TableCell><TableCell align="right">输出</TableCell></>;
}

function LimitCells({ limits, emphasized = false }: { limits: TokenLimits; emphasized?: boolean }) {
  return <>{([limits.maxContextTokens, limits.maxInputTokens, limits.maxOutputTokens] as const).map((value, index) => (
    <TableCell key={index} align="right" sx={{ fontFamily: "ui-monospace, monospace", fontWeight: emphasized ? 700 : 500, color: emphasized ? "primary.dark" : "text.primary" }}>
      {formatTokens(value)}
    </TableCell>
  ))}</>;
}

function ModelLimitDialog({ policy, onClose, onSaved }: { policy: ModelLimitPolicy; onClose: () => void; onSaved: () => void | Promise<void> }) {
  const [context, setContext] = useState(toInput(policy.overrides.maxContextTokens));
  const [input, setInput] = useState(toInput(policy.overrides.maxInputTokens));
  const [output, setOutput] = useState(toInput(policy.overrides.maxOutputTokens));
  const parsed = {
    maxContextTokens: parseLimit(context),
    maxInputTokens: parseLimit(input),
    maxOutputTokens: parseLimit(output),
  };
  const validation = validateLimits(parsed, policy.discovered);
  const save = useMutation({
    mutationFn: () => api.updateModelLimitPolicy({ providerId: policy.providerId, modelId: policy.modelId, ...parsed }),
    onSuccess: onSaved,
  });
  const reset = () => { setContext(""); setInput(""); setOutput(""); };

  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <TuneOutlined color="primary" sx={{ fontSize: 20 }} />
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 740 }}>编辑模型限制</Typography>
          <Typography noWrap color="text.secondary" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>{policy.providerId} / {policy.modelId}</Typography>
        </Box>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.25} sx={{ pt: 0.5 }}>
          {save.error ? <Alert severity="error">{save.error.message}</Alert> : null}
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 1 }}>
            <ReferenceValue label="发现上下文" value={policy.discovered.maxContextTokens} />
            <ReferenceValue label="发现输入" value={policy.discovered.maxInputTokens} />
            <ReferenceValue label="发现输出" value={policy.discovered.maxOutputTokens} />
          </Box>
          <Stack spacing={1.5}>
            <LimitField label="上下文上限" value={context} discovered={policy.discovered.maxContextTokens} onChange={setContext} />
            <LimitField label="输入上限" value={input} discovered={policy.discovered.maxInputTokens} onChange={setInput} />
            <LimitField label="输出上限" value={output} discovered={policy.discovered.maxOutputTokens} onChange={setOutput} />
          </Stack>
          {validation ? <Alert severity="warning">{validation}</Alert> : (
            <Alert severity="success">有效值：上下文 {formatTokens(parsed.maxContextTokens ?? policy.discovered.maxContextTokens)}，输入 {formatTokens(parsed.maxInputTokens ?? policy.discovered.maxInputTokens)}，输出 {formatTokens(parsed.maxOutputTokens ?? policy.discovered.maxOutputTokens)}</Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button startIcon={<RestartAltOutlined />} onClick={reset} disabled={save.isPending}>继承发现值</Button>
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose} disabled={save.isPending}>取消</Button>
        <Button variant="contained" startIcon={<SaveOutlined />} disabled={Boolean(validation) || save.isPending} onClick={() => save.mutate()}>保存</Button>
      </DialogActions>
    </Dialog>
  );
}

function KeepaliveTable({ providers, settings, onEdit }: { providers: ReturnType<typeof providerOptions>; settings: ProviderKeepaliveSettings; onEdit: (providerId: string) => void }) {
  return (
    <Box>
      <Box sx={{ px: 2, height: 52, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
        <Typography sx={{ fontSize: 12.5, fontWeight: 720 }}>厂商保活策略</Typography>
        <Typography color="text.secondary" sx={{ ml: 1, fontSize: 11.5 }}>{providers.length} 个厂商</Typography>
      </Box>
      <TableContainer sx={{ minHeight: 360 }}>
        <Table size="small" sx={{ minWidth: 760 }}>
          <TableHead><TableRow><TableCell>厂商</TableCell><TableCell align="right">保活间隔</TableCell><TableCell align="right">抖动窗口</TableCell><TableCell align="right">参数数</TableCell><TableCell>来源</TableCell><TableCell align="right">操作</TableCell></TableRow></TableHead>
          <TableBody>
            {providers.map(([providerId, label]) => {
              const configured = settings.providers[providerId];
              const value = configured ?? defaultKeepalive;
              return (
                <TableRow key={providerId} hover>
                  <TableCell><Typography sx={{ fontWeight: 700, fontSize: 12.5 }}>{label}</Typography><Typography color="text.secondary" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 10.5 }}>{providerId}</Typography></TableCell>
                  <TableCell align="right">{formatDuration(value.intervalMinutes)}</TableCell>
                  <TableCell align="right">{value.jitterMinutes} 分钟</TableCell>
                  <TableCell align="right">{Object.keys(value.parameters).length}</TableCell>
                  <TableCell><Chip size="small" variant="outlined" color={configured ? "primary" : "default"} label={configured ? "已配置" : "系统默认"} /></TableCell>
                  <TableCell align="right"><Tooltip title="编辑保活策略"><IconButton aria-label={`编辑 ${label} 保活策略`} onClick={() => onEdit(providerId)}><EditOutlined sx={{ fontSize: 17 }} /></IconButton></Tooltip></TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}

function KeepaliveDialog({ providerId, providerName, value, settings, onClose, onSaved }: { providerId: string; providerName: string; value: ProviderKeepalivePolicy; settings: ProviderKeepaliveSettings; onClose: () => void; onSaved: () => void | Promise<void> }) {
  const [intervalMinutes, setIntervalMinutes] = useState(value.intervalMinutes);
  const [jitterMinutes, setJitterMinutes] = useState(value.jitterMinutes);
  const [parameters, setParameters] = useState(JSON.stringify(value.parameters, null, 2));
  const [jsonError, setJsonError] = useState("");
  const save = useMutation({
    mutationFn: () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(parameters || "{}");
      } catch {
        throw new Error("参数必须是有效 JSON");
      }
      if (!isPlainObject(parsed)) throw new Error("参数必须是 JSON 对象");
      return api.updateProviderKeepalive({
        providers: {
          ...settings.providers,
          [providerId]: { intervalMinutes, jitterMinutes, parameters: parsed },
        },
      });
    },
    onSuccess: onSaved,
  });
  const validation = intervalMinutes < 5 || intervalMinutes > 10_080
    ? "保活间隔必须在 5 到 10080 分钟之间"
    : jitterMinutes < 0 || jitterMinutes > intervalMinutes
      ? "抖动窗口必须在 0 与保活间隔之间"
      : jsonError;

  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <Typography sx={{ fontSize: 15, fontWeight: 740 }}>编辑保活策略</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{providerName} · {providerId}</Typography>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          {save.error ? <Alert severity="error">{save.error.message}</Alert> : null}
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))", gap: 1.5 }}>
            <TextField label="保活间隔（分钟）" type="number" value={intervalMinutes} onChange={(event) => setIntervalMinutes(Number(event.target.value))} slotProps={{ htmlInput: { min: 5, max: 10_080 } }} />
            <TextField label="抖动窗口（分钟）" type="number" value={jitterMinutes} onChange={(event) => setJitterMinutes(Number(event.target.value))} slotProps={{ htmlInput: { min: 0, max: intervalMinutes } }} />
          </Box>
          <TextField
            label="远端自动化参数 JSON"
            multiline
            minRows={8}
            value={parameters}
            error={Boolean(jsonError)}
            helperText={jsonError || "保留字段 credential、metadata、proxy_pool、mail 不可覆盖"}
            onChange={(event) => {
              setParameters(event.target.value);
              try {
                const parsed = JSON.parse(event.target.value || "{}");
                setJsonError(isPlainObject(parsed) ? "" : "参数必须是 JSON 对象");
              } catch {
                setJsonError("参数必须是有效 JSON");
              }
            }}
            sx={{ "& textarea": { fontFamily: "ui-monospace, monospace", fontSize: 12 } }}
          />
          {validation && validation !== jsonError ? <Alert severity="warning">{validation}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button startIcon={<RestartAltOutlined />} onClick={() => { setIntervalMinutes(360); setJitterMinutes(20); setParameters("{}"); setJsonError(""); }} disabled={save.isPending}>恢复默认</Button>
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose} disabled={save.isPending}>取消</Button>
        <Button variant="contained" startIcon={<SaveOutlined />} disabled={Boolean(validation) || save.isPending} onClick={() => save.mutate()}>保存</Button>
      </DialogActions>
    </Dialog>
  );
}

function ReferenceValue({ label, value }: { label: string; value: number | null }) {
  return <Box sx={{ p: 1.25, border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "#f8fafb" }}><Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{label}</Typography><Typography sx={{ mt: 0.25, fontFamily: "ui-monospace, monospace", fontWeight: 700, fontSize: 12.5 }}>{formatTokens(value)}</Typography></Box>;
}

function LimitField({ label, value, discovered, onChange }: { label: string; value: string; discovered: number | null; onChange: (value: string) => void }) {
  return <TextField label={label} type="number" value={value} placeholder={discovered ? String(discovered) : "继承未知值"} onChange={(event) => onChange(event.target.value)} helperText={value ? "管理员上限" : "继承厂商发现值"} slotProps={{ htmlInput: { min: 1, max: discovered ?? 100_000_000 } }} />;
}

function validateLimits(overrides: TokenLimits, discovered: TokenLimits) {
  const fields: Array<[keyof TokenLimits, string]> = [["maxContextTokens", "上下文"], ["maxInputTokens", "输入"], ["maxOutputTokens", "输出"]];
  for (const [key, label] of fields) {
    const value = overrides[key];
    const maximum = discovered[key];
    if (value !== null && (!Number.isInteger(value) || value <= 0)) return `${label}上限必须是正整数`;
    if (value !== null && maximum !== null && value > maximum) return `${label}上限不能超过厂商发现值 ${maximum.toLocaleString("zh-CN")}`;
  }
  const context = overrides.maxContextTokens ?? discovered.maxContextTokens;
  const input = overrides.maxInputTokens ?? discovered.maxInputTokens;
  const output = overrides.maxOutputTokens ?? discovered.maxOutputTokens;
  if (context !== null && input !== null && input > context) return "输入上限不能超过上下文上限";
  if (context !== null && output !== null && output > context) return "输出上限不能超过上下文上限";
  return "";
}

function hasOverrides(limits: TokenLimits) {
  return limits.maxContextTokens !== null || limits.maxInputTokens !== null || limits.maxOutputTokens !== null;
}

function parseLimit(value: string) {
  return value.trim() ? Number(value) : null;
}

function toInput(value: number | null) {
  return value === null ? "" : String(value);
}

function formatTokens(value: number | null) {
  return value === null ? "未知" : value.toLocaleString("zh-CN");
}

function formatDuration(minutes: number) {
  if (minutes % 1_440 === 0) return `${minutes / 1_440} 天`;
  if (minutes % 60 === 0) return `${minutes / 60} 小时`;
  return `${minutes} 分钟`;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
