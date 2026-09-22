"use client";

import {
  RefreshOutlined,
  HubOutlined,
  CheckCircleRounded,
  SpeedOutlined,
  LayersOutlined,
  ShieldOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Select,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  type ProviderDescriptor,
  type ProviderModel,
  type ProviderRuntime,
  type ProviderTransportMode,
} from "@/lib/api";
import { PageContainer, PageHeader, DataSurface } from "@/components/page-layout";
import { tokens } from "@/theme/theme";

export function Overview() {
  const queryClient = useQueryClient();
  const models = useQuery({ queryKey: ["models"], queryFn: api.models });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const runtime = useQuery({ queryKey: ["admin-providers"], queryFn: api.adminProviders });
  const health = useQuery({ queryKey: ["health"], queryFn: api.health });
  const rows = groupProviders(runtime.data ?? [], catalog.data?.data ?? [], models.data?.data ?? []);
  const modelRows = models.data?.data ?? [];
  const enabledAccounts = rows.reduce((total, row) => total + row.enabledAccountCount, 0);

  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      api.updateProvider(id, { enabled }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["admin-providers"] }),
        queryClient.invalidateQueries({ queryKey: ["providers"] }),
        queryClient.invalidateQueries({ queryKey: ["models"] }),
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["registration-jobs"] }),
      ]);
    },
  });

  const transportMode = useMutation({
    mutationFn: ({ id, mode }: { id: string; mode: ProviderTransportMode }) =>
      api.updateProvider(id, { transportMode: mode }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-providers"] });
    },
  });

  return (
    <PageContainer maxWidth={1520}>
      <PageHeader
        title="运行概览"
        description="厂商接入、自动化资源、推理通道与集群调度保护的实时控制中枢"
        actions={
          <Tooltip title="刷新集群实时状态">
            <IconButton
              aria-label="刷新状态"
              onClick={() =>
                void Promise.all([catalog.refetch(), runtime.refetch(), models.refetch(), health.refetch()])
              }
              sx={{
                border: `1px solid ${tokens.border}`,
                bgcolor: "background.paper",
                boxShadow: tokens.shadow.sm,
              }}
            >
              <RefreshOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        }
      />

      {(catalog.error || runtime.error || models.error) && (
        <Alert severity="warning" sx={{ mb: 2.5 }}>
          后端尚未连接或探针未返回，启动 Java 服务后将显示实时集群厂商目录。
        </Alert>
      )}
      {toggle.error && <Alert severity="error" sx={{ mb: 2.5 }}>{toggle.error.message}</Alert>}
      {transportMode.error && <Alert severity="error" sx={{ mb: 2.5 }}>{transportMode.error.message}</Alert>}

      {/* Stripe 标杆级三大核心指标卡片 */}
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", md: "repeat(3, 1fr)" },
          gap: 2.25,
          mb: 3,
        }}
      >
        <StatusMetricCard
          label="集群控制面状态"
          value={health.data?.status ?? "未连接"}
          caption="网关与心跳探针链路"
          Icon={SpeedOutlined}
          healthy={health.data?.status === "UP"}
        />
        <StatusMetricCard
          label="已接入厂商"
          value={`${rows.filter((row) => row.enabled).length} / ${rows.length}`}
          caption={`${rows.filter((row) => row.available).length} 家可用供流`}
          Icon={LayersOutlined}
          healthy={rows.some((row) => row.available)}
        />
        <StatusMetricCard
          label="可路由模型总数"
          value={`${modelRows.filter((model) => model.available).length} / ${modelRows.length}`}
          caption="当前全网开放的模型目录"
          Icon={HubOutlined}
          healthy={modelRows.some((model) => model.available)}
        />
      </Box>

      {/* 主数据表与侧面板 */}
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", xl: "minmax(0, 2.3fr) minmax(320px, 1fr)" },
          gap: 2.5,
        }}
      >
        {/* 厂商与模型表格 */}
        <DataSurface>
          <Box sx={{ px: 2.5, py: 2, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, fontSize: 15, letterSpacing: "-0.015em" }}>
                厂商接入与推理路由
              </Typography>
              <Typography color="text.secondary" sx={{ fontSize: 12.5, mt: 0.25 }}>
                动态启用/拔出厂商，实时切换推理通道，无需重启或重新部署
              </Typography>
            </Box>
          </Box>
          <Divider sx={{ borderColor: "divider" }} />
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>厂商名称</TableCell>
                  <TableCell>接入开关</TableCell>
                  <TableCell>健康状态</TableCell>
                  <TableCell>推理通道</TableCell>
                  <TableCell>可用账号</TableCell>
                  <TableCell>绑定模型</TableCell>
                  <TableCell>工具调用</TableCell>
                  <TableCell>多模态能力</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(catalog.isLoading || runtime.isLoading || models.isLoading) && (
                  <TableRow>
                    <TableCell colSpan={8} align="center" sx={{ py: 6 }}>
                      <CircularProgress size={24} thickness={4} />
                    </TableCell>
                  </TableRow>
                )}
                {!catalog.isLoading &&
                  !runtime.isLoading &&
                  !models.isLoading &&
                  rows.map((row) => (
                    <TableRow key={row.id} hover>
                      <TableCell>
                        <Typography sx={{ fontWeight: 650, fontSize: 13.5, color: "text.primary" }}>
                          {row.displayName}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Switch
                          size="small"
                          checked={row.enabled}
                          disabled={!row.installed || (toggle.isPending && toggle.variables?.id === row.id)}
                          onChange={(_, enabled) => {
                            if (!enabled && !window.confirm(`拔出 ${row.displayName}？新请求和后台任务会立即停止。`))
                              return;
                            toggle.mutate({ id: row.id, enabled });
                          }}
                          slotProps={{ input: { "aria-label": `${row.displayName} 接入状态` } }}
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          variant="outlined"
                          color={
                            !row.enabled
                              ? "default"
                              : row.available
                              ? "success"
                              : row.configured
                              ? "warning"
                              : "default"
                          }
                          label={
                            !row.enabled
                              ? "已拔出"
                              : row.available
                              ? "在线"
                              : row.configured
                              ? "待就绪账号"
                              : "待配置"
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <TransportSelector
                          row={row}
                          disabled={transportMode.isPending}
                          onChange={(mode) => transportMode.mutate({ id: row.id, mode })}
                        />
                      </TableCell>
                      <TableCell>
                        <Typography
                          sx={{
                            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                            fontSize: 12.5,
                            fontWeight: 600,
                            color: row.enabledAccountCount > 0 ? "text.primary" : "text.secondary",
                          }}
                        >
                          {row.enabledAccountCount} / {row.accountCount}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography
                          sx={{
                            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
                            fontSize: 12,
                            color: "text.secondary",
                            maxWidth: 180,
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                          }}
                          title={row.models.join(", ") || "-"}
                        >
                          {row.models.join(", ") || "-"}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography sx={{ fontSize: 12, color: "text.secondary" }}>
                          {formatCapability(row.capabilities.FUNCTION_TOOLS)}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={hasMultimodal(row.capabilities) ? "支持模态" : "仅文本"}
                          color={hasMultimodal(row.capabilities) ? "info" : "default"}
                          sx={{ height: 22, fontSize: 11 }}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                {!catalog.isLoading && !runtime.isLoading && !models.isLoading && rows.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={8} align="center" sx={{ py: 6, color: "text.secondary" }}>
                      暂无厂商目录数据
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </DataSurface>

        {/* 右侧：运行面摘要与调度保护 */}
        <Stack spacing={2.5}>
          {/* 运行面摘要 */}
          <Paper
            variant="outlined"
            sx={{
              borderRadius: "14px",
              borderColor: tokens.border,
              overflow: "hidden",
              boxShadow: tokens.shadow.card,
            }}
          >
            <Box sx={{ px: 2.25, py: 1.75, bgcolor: tokens.canvasSubtle }}>
              <Typography variant="h6" sx={{ fontSize: 14, fontWeight: 700 }}>
                集群运行面摘要
              </Typography>
            </Box>
            <Divider sx={{ borderColor: "divider" }} />
            <Stack divider={<Divider flexItem sx={{ borderColor: "divider" }} />}>
              <ResourceRow
                name="启用账号"
                detail="当前参与推理轮询与租约池"
                status={enabledAccounts.toLocaleString("zh-CN")}
              />
              <ResourceRow
                name="已编目模型"
                detail="全网所有可用 Provider 模型"
                status={modelRows.length.toLocaleString("zh-CN")}
              />
              <ResourceRow
                name="运行时不可用"
                detail="经实时健康探针判定"
                status={modelRows
                  .filter((model) => model.runtime.status === "UNAVAILABLE")
                  .length.toLocaleString("zh-CN")}
                highlight={modelRows.some((model) => model.runtime.status === "UNAVAILABLE")}
              />
            </Stack>
          </Paper>

          {/* 调度保护 */}
          <Paper
            variant="outlined"
            sx={{
              borderRadius: "14px",
              borderColor: tokens.border,
              overflow: "hidden",
              boxShadow: tokens.shadow.card,
            }}
          >
            <Box sx={{ px: 2.25, py: 1.75, bgcolor: tokens.canvasSubtle, display: "flex", alignItems: "center", gap: 1 }}>
              <ShieldOutlined sx={{ fontSize: 17, color: "text.secondary" }} />
              <Typography variant="h6" sx={{ fontSize: 14, fontWeight: 700 }}>
                高可用调度防护策略
              </Typography>
            </Box>
            <Divider sx={{ borderColor: "divider" }} />
            <Box sx={{ p: 2 }}>
              <Stack spacing={1.5}>
                <Protection label="确定性抖动 (Deterministic Jitter)" />
                <Protection label="厂商级自适应熔断 (Circuit Breaker)" />
                <Protection label="账号统一分布式租约 (Unified Lease)" />
                <Protection label="到期队列 generation 去重防护" />
              </Stack>
            </Box>
          </Paper>
        </Stack>
      </Box>
    </PageContainer>
  );
}

// Stripe 风格现代指标卡片
function StatusMetricCard({
  label,
  value,
  caption,
  Icon,
  healthy,
}: {
  label: string;
  value: string;
  caption: string;
  Icon: React.ElementType;
  healthy?: boolean;
}) {
  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2.5,
        borderRadius: "14px",
        borderColor: tokens.border,
        bgcolor: "background.paper",
        boxShadow: tokens.shadow.card,
        position: "relative",
        overflow: "hidden",
        display: "flex",
        flexDirection: "column",
        justifyContent: "space-between",
        minHeight: 110,
        transition: "transform 140ms ease, box-shadow 140ms ease",
        "&:hover": {
          transform: "translateY(-1px)",
          boxShadow: tokens.shadow.hover,
        },
      }}
    >
      <Box sx={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
        <Typography
          sx={{
            fontSize: 12.5,
            fontWeight: 600,
            color: "text.secondary",
            letterSpacing: "-0.01em",
          }}
        >
          {label}
        </Typography>
        <Box
          sx={{
            width: 32,
            height: 32,
            borderRadius: "8px",
            bgcolor: healthy ? tokens.status.emerald.light : tokens.canvasSubtle,
            color: healthy ? tokens.status.emerald.main : tokens.text.secondary,
            display: "grid",
            placeItems: "center",
          }}
        >
          <Icon sx={{ fontSize: 18 }} />
        </Box>
      </Box>

      <Box sx={{ mt: 1.5 }}>
        <Typography
          sx={{
            fontSize: "1.75rem",
            fontWeight: 750,
            lineHeight: 1.15,
            letterSpacing: "-0.03em",
            color: "text.primary",
          }}
        >
          {value}
        </Typography>
        <Stack direction="row" spacing={0.75} sx={{ alignItems: "center", mt: 0.5 }}>
          {healthy ? (
            <Box
              sx={{
                width: 6,
                height: 6,
                borderRadius: "50%",
                bgcolor: tokens.status.emerald.main,
                boxShadow: "0 0 0 2px rgba(16, 185, 129, 0.2)",
              }}
            />
          ) : null}
          <Typography sx={{ fontSize: 11.5, color: "text.secondary", fontWeight: 450 }}>
            {caption}
          </Typography>
        </Stack>
      </Box>
    </Paper>
  );
}

function ResourceRow({
  name,
  detail,
  status,
  highlight,
}: {
  name: string;
  detail: string;
  status: string;
  highlight?: boolean;
}) {
  return (
    <Box sx={{ px: 2.25, py: 1.5, display: "flex", alignItems: "center", gap: 1.5 }}>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 650, color: "text.primary" }}>{name}</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>
          {detail}
        </Typography>
      </Box>
      <Typography
        sx={{
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
          fontSize: 13,
          fontWeight: 700,
          color: highlight ? "error.main" : "text.primary",
        }}
      >
        {status}
      </Typography>
    </Box>
  );
}

function Protection({ label }: { label: string }) {
  return (
    <Stack direction="row" spacing={1.25} sx={{ alignItems: "center" }}>
      <CheckCircleRounded sx={{ fontSize: 17, color: tokens.status.emerald.main }} />
      <Typography sx={{ fontSize: 13, fontWeight: 500, color: "text.primary" }}>{label}</Typography>
    </Stack>
  );
}

function groupProviders(
  runtime: ProviderRuntime[],
  providers: ProviderDescriptor[],
  models: ProviderModel[]
) {
  const catalog = new Map(providers.map((provider) => [provider.id, provider]));
  const rows = new Map<
    string,
    {
      id: string;
      displayName: string;
      configured: boolean;
      installed: boolean;
      enabled: boolean;
      accountCount: number;
      enabledAccountCount: number;
      available: boolean;
      models: string[];
      capabilities: Record<string, string>;
      requestedTransportMode: ProviderTransportMode;
      primaryTransportMode: Exclude<ProviderTransportMode, "AUTO">;
      supportedTransportModes: Array<Exclude<ProviderTransportMode, "AUTO">>;
    }
  >();
  for (const provider of runtime) {
    const active = catalog.get(provider.id);
    rows.set(provider.id, {
      id: provider.id,
      displayName: provider.displayName,
      configured: active?.configured ?? false,
      installed: provider.installed,
      enabled: provider.enabled,
      accountCount: provider.accountCount,
      enabledAccountCount: provider.enabledAccountCount,
      available: false,
      models: provider.enabled ? [] : provider.defaultModels,
      capabilities: active?.capabilities ?? provider.capabilities,
      requestedTransportMode: provider.requestedTransportMode,
      primaryTransportMode: provider.primaryTransportMode,
      supportedTransportModes: provider.supportedTransportModes,
    });
  }
  for (const model of models) {
    const current = rows.get(model.owned_by);
    if (!current) continue;
    current.available ||= model.available;
    current.models.push(model.id.includes("/") ? model.id.split("/").slice(1).join("/") : model.id);
  }
  return [...rows.values()];
}

function TransportSelector({
  row,
  disabled,
  onChange,
}: {
  row: {
    requestedTransportMode: ProviderTransportMode;
    supportedTransportModes: Array<Exclude<ProviderTransportMode, "AUTO">>;
  };
  disabled: boolean;
  onChange: (mode: ProviderTransportMode) => void;
}) {
  const options: ProviderTransportMode[] = ["AUTO", ...row.supportedTransportModes];
  return (
    <Select
      size="small"
      variant="outlined"
      value={row.requestedTransportMode}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value as ProviderTransportMode)}
      sx={{
        minWidth: 120,
        height: 30,
        fontSize: 12,
        borderRadius: "6px",
        "& .MuiSelect-select": { py: 0.5, px: 1 },
      }}
      inputProps={{ "aria-label": "推理通道" }}
    >
      {[...new Set(options)].map((mode) => (
        <MenuItem key={mode} value={mode} sx={{ fontSize: 12.5 }}>
          {mode === "AUTO" ? "自动（API优先）" : mode === "API" ? "API 直接通道" : "Runtime 浏览器"}
        </MenuItem>
      ))}
    </Select>
  );
}

function formatCapability(level?: string) {
  if (level === "NATIVE") return "原生支持";
  if (level === "EMULATED") return "模拟支持";
  return "暂不支持";
}

function hasMultimodal(capabilities: Record<string, string>) {
  return ["IMAGE_INPUT", "AUDIO_INPUT", "VIDEO_INPUT", "FILE_INPUT"].some(
    (key) => capabilities[key] === "NATIVE"
  );
}
