"use client";

import {
  AddOutlined,
  ContentCopyOutlined,
  DeleteOutlineOutlined,
  KeyOutlined,
  RefreshOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  IconButton,
  LinearProgress,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  api,
  providerOptions,
  type ApiKeyFeature,
  type ApiKeyProtocol,
  type CreatedDistributionApiKey,
  type DistributionApiKey,
  type ProviderOption,
} from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

const protocolOptions: Array<[ApiKeyProtocol, string]> = [
  ["CHAT_COMPLETIONS", "Chat Completions"],
  ["RESPONSES", "Responses"],
  ["IMAGES", "Images"],
];

const featureOptions: Array<[ApiKeyFeature, string]> = [
  ["MULTIMODAL_INPUT", "多模态输入"],
  ["FILE_UPLOADS", "文件上传"],
  ["TOOL_CALLING", "工具调用"],
];

export function ApiKeys() {
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [created, setCreated] = useState<CreatedDistributionApiKey | null>(null);
  const keys = useQuery({ queryKey: ["api-keys"], queryFn: api.apiKeys });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const models = useQuery({ queryKey: ["models"], queryFn: api.models });
  const providers = providerOptions(catalog.data);
  const providerNames = useMemo(() => new Map(providers), [providers]);
  const update = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      api.updateApiKey(id, enabled),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["api-keys"] }),
  });
  const remove = useMutation({
    mutationFn: api.deleteApiKey,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["api-keys"] }),
  });
  const error = keys.error ?? update.error ?? remove.error;

  return (
    <PageContainer>
      <PageHeader
        title="分发密钥"
        description="为调用方分配厂商、模型与协议级访问范围"
        actions={
          <>
            <Tooltip title="刷新密钥">
              <IconButton
                aria-label="刷新密钥"
                onClick={() => keys.refetch()}
                sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
              >
                <RefreshOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </Tooltip>
            <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setCreateOpen(true)}>
              创建密钥
            </Button>
          </>
        }
      />

      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}
      <DataSurface>
        {keys.isFetching ? <LinearProgress sx={{ position: "absolute", inset: "0 0 auto", height: 2 }} /> : null}
        <TableContainer>
          <Table size="small" sx={{ tableLayout: "fixed", minWidth: 1040 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 130 }}>名称</TableCell>
                <TableCell sx={{ width: 110 }}>密钥前缀</TableCell>
                <TableCell sx={{ width: 220 }}>厂商与模型范围</TableCell>
                <TableCell sx={{ width: 145 }}>协议</TableCell>
                <TableCell sx={{ width: 145 }}>请求功能</TableCell>
                <TableCell sx={{ width: 110 }}>最近使用</TableCell>
                <TableCell sx={{ width: 110 }}>到期时间</TableCell>
                <TableCell align="center" sx={{ width: 55 }}>启用</TableCell>
                <TableCell align="right" sx={{ width: 50 }}>操作</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(keys.data ?? []).map((key) => (
                <ApiKeyRow
                  key={key.id}
                  apiKey={key}
                  providerNames={providerNames}
                  updating={update.isPending && update.variables?.id === key.id}
                  removing={remove.isPending && remove.variables === key.id}
                  onToggle={(enabled) => update.mutate({ id: key.id, enabled })}
                  onDelete={() => {
                    if (window.confirm(`删除分发密钥 ${key.name}？`)) remove.mutate(key.id);
                  }}
                />
              ))}
              {!keys.isLoading && !keys.data?.length ? (
                <TableRow>
                  <TableCell colSpan={9} align="center" sx={{ height: 260, color: "text.secondary" }}>
                    <KeyOutlined sx={{ display: "block", mx: "auto", mb: 1, fontSize: 25, color: "#95a2a7" }} />
                    暂无分发密钥
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
      </DataSurface>

      {createOpen ? (
        <CreateApiKeyDialog
          open
          providers={providers}
          modelsByProvider={groupModels(models.data?.data ?? [])}
          onClose={() => setCreateOpen(false)}
          onCreated={async (value) => {
            setCreateOpen(false);
            setCreated(value);
            await queryClient.invalidateQueries({ queryKey: ["api-keys"] });
          }}
        />
      ) : null}
      {created ? (
        <CreatedSecretDialog
          key={created.key.id}
          value={created}
          onClose={() => setCreated(null)}
        />
      ) : null}
    </PageContainer>
  );
}

function ApiKeyRow({
  apiKey,
  providerNames,
  updating,
  removing,
  onToggle,
  onDelete,
}: {
  apiKey: DistributionApiKey;
  providerNames: Map<string, string>;
  updating: boolean;
  removing: boolean;
  onToggle: (enabled: boolean) => void;
  onDelete: () => void;
}) {
  return (
    <TableRow hover>
      <TableCell>
        <Typography noWrap sx={{ fontWeight: 700, fontSize: 12.5 }}>{apiKey.name}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{formatTime(apiKey.createdAt)}</Typography>
      </TableCell>
      <TableCell>
        <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>
          {apiKey.prefix}...
        </Typography>
      </TableCell>
      <TableCell>
        <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
          {Object.entries(apiKey.providerModels).map(([provider, models]) => (
            <Chip
              key={provider}
              size="small"
              variant="outlined"
              label={`${providerNames.get(provider) ?? provider} · ${models.length ? `${models.length} 模型` : "全部"}`}
            />
          ))}
        </Stack>
      </TableCell>
      <TableCell>
        <Typography noWrap sx={{ fontSize: 11.5 }}>
          {apiKey.protocols.map(protocolLabel).join(" · ")}
        </Typography>
      </TableCell>
      <TableCell>
        <Typography sx={{ fontSize: 11.5 }}>
          {(apiKey.features ?? []).length
            ? apiKey.features.map(featureLabel).join(" · ")
            : "仅文本"}
        </Typography>
      </TableCell>
      <TableCell>{formatTime(apiKey.lastUsedAt)}</TableCell>
      <TableCell>{formatTime(apiKey.expiresAt)}</TableCell>
      <TableCell align="center">
        <Switch
          size="small"
          checked={apiKey.enabled}
          disabled={updating || expired(apiKey.expiresAt)}
          onChange={(_, enabled) => onToggle(enabled)}
          slotProps={{ input: { "aria-label": `${apiKey.name} 启用状态` } }}
        />
      </TableCell>
      <TableCell align="right">
        <Tooltip title="删除密钥">
          <span>
            <IconButton color="error" disabled={removing} onClick={onDelete}>
              <DeleteOutlineOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </span>
        </Tooltip>
      </TableCell>
    </TableRow>
  );
}

function CreateApiKeyDialog({
  open,
  providers,
  modelsByProvider,
  onClose,
  onCreated,
}: {
  open: boolean;
  providers: ProviderOption[];
  modelsByProvider: Map<string, string[]>;
  onClose: () => void;
  onCreated: (value: CreatedDistributionApiKey) => void | Promise<void>;
}) {
  const [name, setName] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [selectedProviders, setSelectedProviders] = useState<Set<string>>(new Set());
  const [allModels, setAllModels] = useState<Set<string>>(new Set());
  const [selectedModels, setSelectedModels] = useState<Record<string, string[]>>({});
  const [protocols, setProtocols] = useState<Set<ApiKeyProtocol>>(
    new Set(["CHAT_COMPLETIONS", "RESPONSES"]),
  );
  const [features, setFeatures] = useState<Set<ApiKeyFeature>>(new Set());
  const create = useMutation({
    mutationFn: () => api.createApiKey({
      name,
      expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
      protocols: [...protocols],
      features: [...features],
      providerModels: Object.fromEntries([...selectedProviders].map((provider) => [
        provider,
        allModels.has(provider) ? [] : (selectedModels[provider] ?? []),
      ])),
    }),
    onSuccess: async (value) => {
      await onCreated(value);
      create.reset();
    },
  });
  const invalidSpecificScope = [...selectedProviders].some(
    (provider) => !allModels.has(provider) && !(selectedModels[provider]?.length),
  );

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>创建分发密钥</DialogTitle>
      <DialogContent>
        <Stack spacing={2.25} sx={{ pt: 1 }}>
          {create.error ? <Alert severity="error">{create.error.message}</Alert> : null}
          <Box sx={{ display: "grid", gridTemplateColumns: "1.4fr 1fr", gap: 2 }}>
            <TextField label="密钥名称" value={name} onChange={(event) => setName(event.target.value)} />
            <TextField
              label="到期时间"
              type="datetime-local"
              value={expiresAt}
              onChange={(event) => setExpiresAt(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          </Box>

          <Box>
            <Typography sx={{ mb: 0.75, fontSize: 12.5, fontWeight: 700 }}>允许协议</Typography>
            <FormGroup row sx={{ gap: 2 }}>
              {protocolOptions.map(([protocol, label]) => (
                <FormControlLabel
                  key={protocol}
                  label={label}
                  control={
                    <Checkbox
                      size="small"
                      checked={protocols.has(protocol)}
                      onChange={(_, checked) => setProtocols((current) => {
                        const next = new Set(current);
                        if (checked) next.add(protocol); else next.delete(protocol);
                        return next;
                      })}
                    />
                  }
                />
              ))}
            </FormGroup>
          </Box>

          <Box>
            <Typography sx={{ mb: 0.75, fontSize: 12.5, fontWeight: 700 }}>允许请求功能</Typography>
            <FormGroup row sx={{ gap: 2 }}>
              {featureOptions.map(([feature, label]) => (
                <FormControlLabel
                  key={feature}
                  label={label}
                  control={
                    <Checkbox
                      size="small"
                      checked={features.has(feature)}
                      onChange={(_, checked) => setFeatures((current) => {
                        const next = new Set(current);
                        if (checked) next.add(feature); else next.delete(feature);
                        return next;
                      })}
                    />
                  }
                />
              ))}
            </FormGroup>
          </Box>

          <Box>
            <Typography sx={{ mb: 1, fontSize: 12.5, fontWeight: 700 }}>厂商与模型范围</Typography>
            <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1, overflow: "hidden" }}>
              {providers.map(([provider, displayName], index) => {
                const selected = selectedProviders.has(provider);
                const everyModel = allModels.has(provider);
                return (
                  <Box
                    key={provider}
                    sx={{
                      display: "grid",
                      gridTemplateColumns: "180px 190px minmax(260px, 1fr)",
                      gap: 1.5,
                      alignItems: "center",
                      minHeight: 58,
                      px: 1.5,
                      py: 1,
                      borderBottom: index === providers.length - 1 ? 0 : 1,
                      borderColor: "divider",
                      bgcolor: selected ? "#f8fbfb" : "background.paper",
                    }}
                  >
                    <FormControlLabel
                      label={<Box><Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>{displayName}</Typography><Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{provider}</Typography></Box>}
                      control={
                        <Checkbox
                          size="small"
                          checked={selected}
                          onChange={(_, checked) => setSelectedProviders((current) => {
                            const next = new Set(current);
                            if (checked) {
                              next.add(provider);
                              setAllModels((values) => new Set(values).add(provider));
                            } else {
                              next.delete(provider);
                            }
                            return next;
                          })}
                        />
                      }
                    />
                    <ToggleButtonGroup
                      exclusive
                      size="small"
                      value={everyModel ? "all" : "selected"}
                      disabled={!selected}
                      onChange={(_, value) => {
                        if (!value) return;
                        setAllModels((current) => {
                          const next = new Set(current);
                          if (value === "all") next.add(provider); else next.delete(provider);
                          return next;
                        });
                      }}
                    >
                      <ToggleButton value="all">全部模型</ToggleButton>
                      <ToggleButton value="selected">指定模型</ToggleButton>
                    </ToggleButtonGroup>
                    <Autocomplete
                      multiple
                      size="small"
                      disableCloseOnSelect
                      disabled={!selected || everyModel}
                      options={modelsByProvider.get(provider) ?? []}
                      value={selectedModels[provider] ?? []}
                      onChange={(_, value) => setSelectedModels((current) => ({ ...current, [provider]: value }))}
                      renderInput={(params) => <TextField {...params} placeholder="选择模型" />}
                    />
                  </Box>
                );
              })}
            </Box>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button
          variant="contained"
          disabled={create.isPending || !name.trim() || !selectedProviders.size || !protocols.size || invalidSpecificScope}
          onClick={() => create.mutate()}
        >
          创建
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function CreatedSecretDialog({
  value,
  onClose,
}: {
  value: CreatedDistributionApiKey | null;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <Dialog open={Boolean(value)} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>分发密钥已创建</DialogTitle>
      <DialogContent>
        <Alert severity="warning" sx={{ mb: 2 }}>密钥明文只显示这一次，关闭后无法再次查看。</Alert>
        <TextField
          fullWidth
          label="密钥"
          value={value?.secret ?? ""}
          slotProps={{ input: { readOnly: true } }}
          sx={{ "& input": { fontFamily: "ui-monospace, monospace", fontSize: 12 } }}
        />
      </DialogContent>
      <DialogActions>
        <Button
          startIcon={<ContentCopyOutlined />}
          onClick={async () => {
            if (!value) return;
            await navigator.clipboard.writeText(value.secret);
            setCopied(true);
          }}
        >
          {copied ? "已复制" : "复制密钥"}
        </Button>
        <Button variant="contained" onClick={onClose}>完成</Button>
      </DialogActions>
    </Dialog>
  );
}

function groupModels(models: Array<{ id: string; owned_by: string }>) {
  const grouped = new Map<string, string[]>();
  for (const model of models) {
    const prefix = `${model.owned_by}/`;
    const upstream = model.id.startsWith(prefix) ? model.id.slice(prefix.length) : model.id;
    const current = grouped.get(model.owned_by) ?? [];
    current.push(upstream);
    grouped.set(model.owned_by, current);
  }
  return grouped;
}

function protocolLabel(protocol: ApiKeyProtocol) {
  return protocolOptions.find(([value]) => value === protocol)?.[1] ?? protocol;
}

function featureLabel(feature: ApiKeyFeature) {
  return featureOptions.find(([value]) => value === feature)?.[1] ?? feature;
}

function formatTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
    : "-";
}

function expired(value: string | null) {
  return value ? new Date(value).getTime() <= Date.now() : false;
}
