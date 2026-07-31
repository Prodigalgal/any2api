"use client";

import {
  CheckCircleOutlined,
  ErrorOutlined,
  RefreshOutlined,
  ScheduleOutlined
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type ProviderDescriptor, type ProviderModel, type ProviderRuntime } from "@/lib/api";

export function Overview() {
  const queryClient = useQueryClient();
  const models = useQuery({ queryKey: ["models"], queryFn: api.models });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const runtime = useQuery({ queryKey: ["admin-providers"], queryFn: api.adminProviders });
  const health = useQuery({ queryKey: ["health"], queryFn: api.health });
  const rows = groupProviders(runtime.data ?? [], catalog.data?.data ?? [], models.data?.data ?? []);
  const toggle = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => api.updateProvider(id, enabled),
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

  return (
    <Box sx={{ px: { xs: 1.5, sm: 2.5, lg: 3.5 }, py: { xs: 2, sm: 3 }, maxWidth: 1440, mx: "auto" }}>
      <Stack
        direction={{ xs: "column", sm: "row" }}
        spacing={2}
        sx={{ mb: 2.5, alignItems: { sm: "flex-start" }, justifyContent: "space-between" }}
      >
        <Box>
          <Typography variant="h4">运行概览</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5, fontSize: 13 }}>厂商接入、账号生命周期与自动化资源的实时状态</Typography>
        </Box>
        <Button variant="outlined" startIcon={<RefreshOutlined />} onClick={() => void Promise.all([catalog.refetch(), runtime.refetch(), models.refetch(), health.refetch()])}>刷新状态</Button>
      </Stack>

      {(catalog.error || runtime.error || models.error) && <Alert severity="warning" sx={{ mb: 2 }}>后端尚未连接，启动 Java 服务后将显示真实厂商目录。</Alert>}
      {toggle.error && <Alert severity="error" sx={{ mb: 2 }}>{toggle.error.message}</Alert>}

      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(3, 1fr)" }, border: 1, borderColor: "divider", bgcolor: "background.paper", mb: 2.5 }}>
        <StatusMetric label="控制面" value={health.data?.status ?? "未连接"} healthy={health.data?.status === "UP"} />
        <StatusMetric label="已接入厂商" value={`${rows.filter((row) => row.enabled).length} / ${rows.length}`} healthy={rows.some((row) => row.available)} />
        <StatusMetric label="生命周期积压" value="尚未采集" icon="schedule" />
      </Box>

      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", xl: "minmax(0, 2fr) minmax(300px, 1fr)" }, gap: 2.5 }}>
        <Paper variant="outlined" sx={{ overflow: "hidden" }}>
          <Box sx={{ px: 2, py: 1.75 }}>
            <Typography variant="h6">厂商与模型</Typography>
            <Typography color="text.secondary" sx={{ fontSize: 12, mt: 0.25 }}>开关即时控制路由、模型目录与后台任务，无需重新部署</Typography>
          </Box>
          <Divider />
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>厂商</TableCell><TableCell>接入</TableCell><TableCell>状态</TableCell><TableCell>账号</TableCell><TableCell>默认模型</TableCell><TableCell>工具</TableCell><TableCell>多模态</TableCell></TableRow></TableHead>
              <TableBody>
                {(catalog.isLoading || runtime.isLoading || models.isLoading) && <TableRow><TableCell colSpan={7} align="center" sx={{ py: 5 }}><CircularProgress size={24} /></TableCell></TableRow>}
                {!catalog.isLoading && !runtime.isLoading && !models.isLoading && rows.map((row) => (
                  <TableRow key={row.id} hover>
                    <TableCell><Typography sx={{ fontWeight: 700, fontSize: 13 }}>{row.displayName}</Typography></TableCell>
                    <TableCell>
                      <Switch
                        size="small"
                        checked={row.enabled}
                        disabled={!row.installed || (toggle.isPending && toggle.variables?.id === row.id)}
                        onChange={(_, enabled) => {
                          if (!enabled && !window.confirm(`拔出 ${row.displayName}？新请求和后台任务会立即停止。`)) return;
                          toggle.mutate({ id: row.id, enabled });
                        }}
                        slotProps={{ input: { "aria-label": `${row.displayName} 接入状态` } }}
                      />
                    </TableCell>
                    <TableCell><Chip size="small" variant="outlined" color={row.available ? "success" : "default"} label={!row.enabled ? "已拔出" : row.available ? "可用" : row.configured ? "待账号" : "待配置"} /></TableCell>
                    <TableCell><Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12 }}>{row.enabledAccountCount} / {row.accountCount}</Typography></TableCell>
                    <TableCell><Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12 }}>{row.models.join(", ") || "-"}</Typography></TableCell>
                    <TableCell>{formatCapability(row.capabilities.FUNCTION_TOOLS)}</TableCell>
                    <TableCell>{hasMultimodal(row.capabilities) ? "支持" : "文本"}</TableCell>
                  </TableRow>
                ))}
                {!catalog.isLoading && !runtime.isLoading && !models.isLoading && rows.length === 0 && <TableRow><TableCell colSpan={7} align="center" sx={{ py: 5, color: "text.secondary" }}>等待后端厂商目录</TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        </Paper>

        <Stack spacing={2.5}>
          <Paper variant="outlined">
            <Box sx={{ px: 2, py: 1.75 }}><Typography variant="h6">自动化资源</Typography></Box>
            <Divider />
            <Stack divider={<Divider flexItem />}>
              <ResourceRow name="实时浏览器 lane" detail="高优先级风控状态" status="等待连接" />
              <ResourceRow name="批处理浏览器 lane" detail="注册与重新登录" status="等待连接" />
              <ResourceRow name="本地打码" detail="OCR、滑块、点选、连线" status="等待连接" />
            </Stack>
          </Paper>
          <Paper variant="outlined">
            <Box sx={{ px: 2, py: 1.75 }}><Typography variant="h6">调度保护</Typography></Box>
            <Divider />
            <Box sx={{ p: 2 }}>
              <Stack spacing={1.25}>
                <Protection label="确定性抖动" />
                <Protection label="厂商级熔断" />
                <Protection label="账号统一租约" />
                <Protection label="到期队列 generation 去重" />
              </Stack>
            </Box>
          </Paper>
        </Stack>
      </Box>
    </Box>
  );
}

function StatusMetric({ label, value, healthy, icon }: { label: string; value: string; healthy?: boolean; icon?: string }) {
  const Icon = icon === "schedule" ? ScheduleOutlined : healthy ? CheckCircleOutlined : ErrorOutlined;
  return (
    <Box sx={{ px: 2.25, py: 2, minHeight: 86, display: "flex", alignItems: "center", gap: 1.5, borderRight: { sm: 1 }, borderBottom: { xs: 1, sm: 0 }, borderColor: "divider", "&:last-child": { borderRight: 0, borderBottom: 0 } }}>
      <Box sx={{ width: 34, height: 34, display: "grid", placeItems: "center", borderRadius: 1, bgcolor: healthy ? "#e5f4ee" : "#eef1f2", color: healthy ? "success.main" : "text.secondary" }}><Icon sx={{ fontSize: 20 }} /></Box>
      <Box><Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{label}</Typography><Typography sx={{ fontSize: 18, fontWeight: 750, mt: 0.15 }}>{value}</Typography></Box>
    </Box>
  );
}

function ResourceRow({ name, detail, status }: { name: string; detail: string; status: string }) {
  return <Box sx={{ px: 2, py: 1.5, display: "flex", alignItems: "center", gap: 1.5 }}><Box sx={{ flex: 1 }}><Typography sx={{ fontSize: 13, fontWeight: 650 }}>{name}</Typography><Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{detail}</Typography></Box><Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{status}</Typography></Box>;
}

function Protection({ label }: { label: string }) {
  return <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}><CheckCircleOutlined color="success" sx={{ fontSize: 17 }} /><Typography sx={{ fontSize: 12.5 }}>{label}</Typography></Stack>;
}

function groupProviders(runtime: ProviderRuntime[], providers: ProviderDescriptor[], models: ProviderModel[]) {
  const catalog = new Map(providers.map((provider) => [provider.id, provider]));
  const rows = new Map<string, { id: string; displayName: string; configured: boolean; installed: boolean; enabled: boolean; accountCount: number; enabledAccountCount: number; available: boolean; models: string[]; capabilities: Record<string, string> }>();
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

function formatCapability(level?: string) {
  if (level === "NATIVE") return "原生";
  if (level === "EMULATED") return "模拟";
  return "不支持";
}

function hasMultimodal(capabilities: Record<string, string>) {
  return ["IMAGE_INPUT", "AUDIO_INPUT", "VIDEO_INPUT", "FILE_INPUT"].some((key) => capabilities[key] === "NATIVE");
}
