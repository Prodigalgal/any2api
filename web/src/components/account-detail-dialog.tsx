"use client";

import {
  CloseOutlined,
  LoginOutlined,
  MonitorHeartOutlined,
  PlayCircleOutlineOutlined,
  RefreshOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Skeleton,
  Stack,
  Switch,
  Tab,
  Tabs,
  Tooltip,
  Typography,
  useMediaQuery,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api, type Account } from "@/lib/api";
import { OperationEventsTable } from "@/components/operation-events-dialog";

const hiddenMetadataKey = /(authorization|cookie|credential|key|password|secret|session|sso|token)/i;

export function AccountDetailDialog({ account, providerName, reauthenticationSupported, onClose, onProbe }: { account: Account; providerName: string; reauthenticationSupported: boolean; onClose: () => void; onProbe: () => void }) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down("sm"));
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const detail = useQuery({ queryKey: ["account-detail", account.id], queryFn: () => api.accountDetail(account.id) });
  const events = useQuery({ queryKey: ["account-events", account.id], queryFn: () => api.accountEvents(account.id), enabled: tab === 1 });
  const invalidate = async () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ["account-detail", account.id] }),
    queryClient.invalidateQueries({ queryKey: ["accounts-page"] }),
  ]);
  const reauthenticate = useMutation({ mutationFn: () => api.reauthenticateAccount(account.id), onSuccess: invalidate });
  const activate = useMutation({ mutationFn: () => api.activateAccount(account.id), onSuccess: invalidate });
  const update = useMutation({
    mutationFn: () => api.updateAccount(account.id, { enabled: false, status: "DISABLED" }),
    onSuccess: invalidate,
  });
  const metadata = useMemo(() => metadataEntries(detail.data?.account.metadata ?? {}), [detail.data?.account.metadata]);
  const current = detail.data?.account ?? account;
  const error = detail.error ?? events.error ?? reauthenticate.error ?? activate.error ?? update.error;

  return (
    <Dialog
      open
      onClose={onClose}
      maxWidth="xl"
      fullWidth
      fullScreen={fullScreen}
      slotProps={{ paper: { sx: { height: { sm: "min(90vh, 900px)" }, maxHeight: { sm: 900 } } } }}
    >
      <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1.25, borderBottom: 1, borderColor: "divider" }}>
        <Box sx={{ width: 36, height: 36, borderRadius: 1, display: "grid", placeItems: "center", bgcolor: "primary.light", color: "primary.dark", fontWeight: 780, fontSize: 12 }}>
          {providerName.slice(0, 2).toUpperCase()}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography noWrap sx={{ maxWidth: { xs: 180, sm: 420 }, fontSize: 15, fontWeight: 750 }}>{current.email || current.externalId}</Typography>
            <StatusChip status={current.status} />
          </Stack>
          <Typography noWrap color="text.secondary" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 10.5 }}>{providerName} · {current.externalId}</Typography>
        </Box>
        <Box sx={{ flex: 1 }} />
        <Tooltip title="刷新详情"><span><IconButton aria-label="刷新详情" onClick={() => void Promise.all([detail.refetch(), events.refetch()])} disabled={detail.isFetching}><RefreshOutlined sx={{ fontSize: 18 }} /></IconButton></span></Tooltip>
        <Tooltip title="关闭"><IconButton aria-label="关闭详情" onClick={onClose}><CloseOutlined sx={{ fontSize: 19 }} /></IconButton></Tooltip>
      </DialogTitle>
      <Box sx={{ display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider", px: { xs: 1, sm: 2 } }}>
        <Tabs value={tab} onChange={(_, value: number) => setTab(value)}>
          <Tab label="账户概览" />
          <Tab label="生命周期" />
        </Tabs>
        <Box sx={{ flex: 1 }} />
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          {!current.enabled ? (
            <Button size="small" startIcon={<PlayCircleOutlineOutlined />} disabled={activate.isPending || current.status === "BANNED"} onClick={() => activate.mutate()}>激活入池</Button>
          ) : reauthenticationSupported ? (
            <Button size="small" startIcon={<LoginOutlined />} disabled={reauthenticate.isPending} onClick={() => reauthenticate.mutate()}>重新认证</Button>
          ) : null}
          <Button size="small" variant="contained" startIcon={<MonitorHeartOutlined />} onClick={onProbe}>发起探测</Button>
        </Stack>
      </Box>
      <DialogContent sx={{ p: 0, overflow: "auto" }}>
        {detail.isLoading ? <DetailSkeleton /> : null}
        {error ? <Alert severity="error" sx={{ m: 2 }}>{error.message}</Alert> : null}
        {activate.data ? (
          <Alert severity="info" sx={{ m: 2 }}>
            激活任务已排队：{activate.data.action === "PROBE" ? "真实探测" : "重新认证后探测"}
          </Alert>
        ) : null}
        {!detail.isLoading && tab === 0 && detail.data ? (
          <Box>
            <Box sx={{ display: "grid", gridTemplateColumns: { xs: "repeat(2, 1fr)", md: "repeat(5, 1fr)" }, borderBottom: 1, borderColor: "divider" }}>
              <Metric label="账户状态" value={statusLabel(current.status)} tone={current.status === "ACTIVE" ? "success.main" : "warning.main"} />
              <Metric label="推理就绪" value={metadataValue(detail.data.account.metadata, "inference_probe_status") || "未探测"} />
              <Metric label="累计请求" value={current.requestCount.toLocaleString("zh-CN")} />
              <Metric label="成功请求" value={current.successCount.toLocaleString("zh-CN")} tone="success.main" />
              <Metric label="失败请求" value={current.failureCount.toLocaleString("zh-CN")} tone={current.failureCount ? "error.main" : undefined} />
            </Box>
            <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" }, borderBottom: 1, borderColor: "divider" }}>
              <DetailSection title="账户与凭据">
                <DetailGrid>
                  <DetailField label="账户 ID" value={current.id} mono />
                  <DetailField label="厂商" value={providerName} />
                  <DetailField label="账户到期" value={formatTime(current.expiresAt, "长期")} />
                  <DetailField label="冷却结束" value={formatTime(detail.data.account.cooldownUntil, "无冷却")} />
                  <DetailField label="凭据类型" value={detail.data.credential.type || "-"} mono />
                  <DetailField label="凭据版本" value={detail.data.credential.configured ? `v${detail.data.credential.version}` : "未配置"} mono />
                  <DetailField label="凭据到期" value={formatTime(detail.data.credential.expiresAt, "长期")} />
                  <DetailField label="最近成功" value={formatTime(detail.data.account.lastSuccessAt)} />
                </DetailGrid>
                <Stack direction="row" spacing={1.25} sx={{ minHeight: 54, px: 2, alignItems: "center", borderTop: 1, borderColor: "divider" }}>
                  <Switch
                    size="small"
                    checked={current.enabled}
                    disabled={update.isPending || activate.isPending || (!current.enabled && current.status === "BANNED")}
                    onChange={(_, enabled) => {
                      if (enabled) activate.mutate();
                      else update.mutate();
                    }}
                  />
                  <Box><Typography sx={{ fontSize: 12.5, fontWeight: 680 }}>参与推理路由</Typography><Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{current.enabled ? "已启用" : activate.isPending ? "正在排队激活" : "已停用，开启需通过探针"}</Typography></Box>
                </Stack>
              </DetailSection>
              <DetailSection title="环境与亲和">
                <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" } }}>
                  {metadata.filter(([key]) => isEnvironmentMetadata(key)).slice(0, 12).map(([key, value]) => <DetailField key={key} label={key} value={value} mono />)}
                  {metadata.filter(([key]) => isEnvironmentMetadata(key)).length === 0 ? <Typography color="text.secondary" sx={{ p: 2, fontSize: 12 }}>暂无环境亲和元数据</Typography> : null}
                </Box>
              </DetailSection>
            </Box>
            <DetailSection title="运行元数据">
              <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", xl: "repeat(3, minmax(0, 1fr))" } }}>
                {metadata.filter(([key]) => !isEnvironmentMetadata(key)).map(([key, value]) => <DetailField key={key} label={key} value={value} mono />)}
                {metadata.length === 0 ? <Typography color="text.secondary" sx={{ p: 2, fontSize: 12 }}>暂无运行元数据</Typography> : null}
              </Box>
            </DetailSection>
          </Box>
        ) : null}
        {tab === 1 ? <OperationEventsTable events={events.data ?? []} isFetching={events.isFetching} error={events.error} maxHeight={720} /> : null}
      </DialogContent>
    </Dialog>
  );
}

function DetailSection({ title, children }: { title: string; children: React.ReactNode }) {
  return <Box sx={{ minWidth: 0, borderRight: { lg: 1 }, borderColor: "divider", "&:last-child": { borderRight: 0 } }}><Box sx={{ height: 42, px: 2, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}><Typography sx={{ fontSize: 12.5, fontWeight: 720 }}>{title}</Typography></Box>{children}</Box>;
}
function DetailGrid({ children }: { children: React.ReactNode }) { return <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" } }}>{children}</Box>; }
function DetailField({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) { return <Box sx={{ minWidth: 0, px: 2, py: 1.25, borderBottom: 1, borderRight: 1, borderColor: "divider" }}><Typography color="text.secondary" sx={{ mb: 0.35, fontSize: 10.5 }}>{label}</Typography><Typography title={value} noWrap sx={{ fontFamily: mono ? "ui-monospace, monospace" : undefined, fontSize: 12 }}>{value}</Typography></Box>; }
function Metric({ label, value, tone }: { label: string; value: string; tone?: string }) { return <Box sx={{ minWidth: 0, px: 2, py: 1.5, borderRight: 1, borderBottom: { xs: 1, md: 0 }, borderColor: "divider" }}><Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{label}</Typography><Typography noWrap title={value} sx={{ mt: 0.35, color: tone, fontSize: 17, fontWeight: 750 }}>{value}</Typography></Box>; }
function StatusChip({ status }: { status: string }) { const color = status === "ACTIVE" ? "success" : status === "PENDING" ? "warning" : status === "DEGRADED" ? "error" : "default"; return <Chip size="small" color={color} variant="outlined" label={statusLabel(status)} />; }
function statusLabel(status: string) { return ({ ACTIVE: "正常", PENDING: "待就绪", DEGRADED: "异常", BANNED: "封禁", DISABLED: "停用", EXPIRED: "过期" } as Record<string, string>)[status] ?? status; }
function metadataEntries(metadata: Record<string, unknown>): Array<[string, string]> { return Object.entries(metadata).sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => [key, hiddenMetadataKey.test(key) ? "已隐藏" : formatMetadata(value)]); }
function isEnvironmentMetadata(key: string) { return /(fingerprint|identity|proxy|affinity|browser|user_agent|timezone|locale|language|webgl|canvas|screen|platform)/i.test(key); }
function metadataValue(metadata: Record<string, unknown>, key: string) { const value = metadata[key]; return value === undefined || value === null ? "" : String(value); }
function formatMetadata(value: unknown) { if (value === null || value === undefined) return "-"; if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value); const serialized = JSON.stringify(value); return serialized.length > 240 ? `${serialized.slice(0, 237)}...` : serialized; }
function formatTime(value: string | null | undefined, empty = "-") { return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : empty; }
function DetailSkeleton() { return <Stack spacing={1.5} sx={{ p: 2 }}><Skeleton variant="rectangular" height={92} /><Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" }, gap: 1.5 }}><Skeleton variant="rectangular" height={330} /><Skeleton variant="rectangular" height={330} /></Box></Stack>; }
