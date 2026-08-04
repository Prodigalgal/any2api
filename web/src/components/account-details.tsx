"use client";

import {
  ArrowBackOutlined,
  CheckCircleOutlined,
  ErrorOutlineOutlined,
  LoginOutlined,
  MonitorHeartOutlined,
  MoreVertOutlined,
  RefreshOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Menu,
  MenuItem,
  Skeleton,
  Stack,
  Switch,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useMemo, useState, type MouseEvent } from "react";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";
import { OperationEventsTable } from "@/components/operation-events-dialog";
import { api } from "@/lib/api";

const hiddenMetadataKey = /(authorization|cookie|credential|password|secret|session|sso|token)/i;

export function AccountDetails({ accountId }: { accountId: string }) {
  const queryClient = useQueryClient();
  const [commandAnchor, setCommandAnchor] = useState<HTMLElement | null>(null);
  const detail = useQuery({
    queryKey: ["account-detail", accountId],
    queryFn: () => api.accountDetail(accountId),
    refetchInterval: (query) => query.state.data?.account.status === "PENDING" ? 2_000 : false,
  });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const commands = useQuery({
    queryKey: ["account-commands", accountId],
    queryFn: () => api.accountCommands(accountId),
  });
  const events = useQuery({
    queryKey: ["account-events", accountId],
    queryFn: () => api.accountEvents(accountId),
    refetchInterval: (query) => (
      detail.data?.account.status === "PENDING"
      || query.state.data?.some((event) => event.status === "RUNNING")
    ) ? 2_000 : false,
  });
  const provider = catalog.data?.data.find(
    (item) => item.id === detail.data?.account.providerId,
  );
  const probeSupported = provider?.lifecycleOperations.includes("keepalive") ?? false;
  const reauthenticationSupported = provider?.lifecycleOperations.includes("reauthenticate") ?? false;

  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["account-detail", accountId] }),
      queryClient.invalidateQueries({ queryKey: ["account-events", accountId] }),
      queryClient.invalidateQueries({ queryKey: ["accounts-page"] }),
      queryClient.invalidateQueries({ queryKey: ["admin-providers"] }),
    ]);
  };
  const probe = useMutation({
    mutationFn: () => api.probeAccount(accountId),
    onSuccess: invalidate,
  });
  const reauthenticate = useMutation({
    mutationFn: () => api.reauthenticateAccount(accountId),
    onSuccess: invalidate,
  });
  const update = useMutation({
    mutationFn: (enabled: boolean) => api.updateAccount(accountId, {
      enabled,
      status: enabled ? "ACTIVE" : "DISABLED",
    }),
    onSuccess: invalidate,
  });
  const executeCommand = useMutation({
    mutationFn: (command: string) => api.executeAccountCommand(accountId, command),
    onSuccess: async () => {
      setCommandAnchor(null);
      await invalidate();
    },
  });

  const error = detail.error ?? catalog.error ?? commands.error ?? events.error
    ?? probe.error ?? reauthenticate.error ?? update.error ?? executeCommand.error;
  const metadata = useMemo(
    () => metadataEntries(detail.data?.account.metadata ?? {}),
    [detail.data?.account.metadata],
  );

  if (detail.isLoading) return <AccountDetailsSkeleton />;
  if (!detail.data) {
    return (
      <PageContainer>
        <Alert severity="error">{detail.error?.message ?? "账号不存在或无法读取"}</Alert>
      </PageContainer>
    );
  }

  const value = detail.data;
  const account = value.account;
  const readiness = stringMetadata(account.metadata, "inference_probe_status") || "未探测";
  const eventRunning = events.data?.some((event) => event.status === "RUNNING") ?? false;
  const checking = probe.isPending || eventRunning;
  const awaitingRetry = account.status === "PENDING" && !checking;
  const probeBlocked = checking || awaitingRetry;
  const displayedReadiness = checking && readiness === "READY" ? "CHECKING" : readiness;

  return (
    <PageContainer maxWidth={1480}>
      <PageHeader
        title={account.email || account.externalId}
        description={`${provider?.displayName ?? account.providerId} · ${account.externalId}`}
        actions={
          <>
            <Tooltip title="返回账号池">
              <IconButton
                component={Link}
                href="/accounts"
                aria-label="返回账号池"
                sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
              >
                <ArrowBackOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title="刷新详情">
              <span>
                <IconButton
                  aria-label="刷新详情"
                  disabled={detail.isFetching}
                  onClick={() => void Promise.all([detail.refetch(), events.refetch()])}
                  sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
                >
                  <RefreshOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title={probeSupported ? "执行账号测活" : "该厂商不支持测活"}>
              <span>
                <Button
                  variant="contained"
                  startIcon={checking ? <CircularProgress size={15} color="inherit" /> : <MonitorHeartOutlined />}
                  disabled={!probeSupported || probeBlocked}
                  onClick={() => probe.mutate()}
                >
                  {checking ? "测活中" : awaitingRetry ? "待复测" : "立即测活"}
                </Button>
              </span>
            </Tooltip>
          </>
        }
      />

      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}
      {probe.isSuccess && readiness === "FAILED" ? (
        <Alert severity="warning" sx={{ mb: 2 }}>本次测活未通过，账号已退出推理路由并进入自动复测队列。</Alert>
      ) : probe.isSuccess && account.status === "PENDING" ? (
        <Alert severity="info" sx={{ mb: 2 }}>测活任务已进入生命周期队列，结果将在本页自动更新。</Alert>
      ) : null}

      <DataSurface sx={{ mb: 2 }}>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: "minmax(280px, 1.4fr) repeat(4, minmax(130px, 0.8fr))",
            minHeight: 96,
          }}
        >
          <SummaryCell label="账号状态">
            <StatusValue status={account.status} error={account.lastError} />
          </SummaryCell>
          <SummaryCell label="推理就绪"><ReadinessValue value={displayedReadiness} /></SummaryCell>
          <SummaryCell label="累计请求" value={formatNumber(account.requestCount)} />
          <SummaryCell label="成功请求" value={formatNumber(account.successCount)} tone="success.main" />
          <SummaryCell label="连续失败" value={formatNumber(account.failureCount)} tone={account.failureCount ? "error.main" : undefined} />
        </Box>
      </DataSurface>

      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1.15fr) minmax(380px, 0.85fr)", gap: 2, mb: 2 }}>
        <DataSurface>
          <SectionHeader title="账号信息" />
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
            <DetailField label="账号 ID" value={account.id} mono />
            <DetailField label="上游账号" value={account.externalId} mono />
            <DetailField label="厂商" value={provider?.displayName ?? account.providerId} />
            <DetailField label="邮箱" value={account.email || "-"} />
            <DetailField label="账号到期" value={formatTime(account.expiresAt, "长期")} />
            <DetailField label="冷却结束" value={formatTime(account.cooldownUntil, "无冷却")} />
            <DetailField label="最近使用" value={formatTime(account.lastUsedAt)} />
            <DetailField label="最近成功" value={formatTime(account.lastSuccessAt)} />
            <DetailField label="最近失败" value={formatTime(account.lastFailureAt)} />
            <DetailField label="更新时间" value={formatTime(account.updatedAt)} />
          </Box>
          <Divider />
          <Stack direction="row" spacing={2} sx={{ minHeight: 58, px: 2, alignItems: "center" }}>
            <Switch
              size="small"
              checked={account.enabled}
              disabled={update.isPending || probeBlocked}
              onChange={(_, enabled) => update.mutate(enabled)}
              slotProps={{ input: { "aria-label": "账号启用状态" } }}
            />
            <Box sx={{ minWidth: 130 }}>
              <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>参与推理路由</Typography>
              <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{account.enabled ? "已启用" : "已停用"}</Typography>
            </Box>
            <Box sx={{ flex: 1 }} />
            {reauthenticationSupported ? (
              <Button
                size="small"
                startIcon={<LoginOutlined />}
                disabled={reauthenticate.isPending || probeBlocked}
                onClick={() => reauthenticate.mutate()}
              >
                重新认证
              </Button>
            ) : null}
            <Button
              size="small"
              endIcon={<MoreVertOutlined />}
              disabled={commands.isLoading || (commands.data?.length ?? 0) === 0}
              onClick={(event: MouseEvent<HTMLElement>) => setCommandAnchor(event.currentTarget)}
            >
              厂商操作
            </Button>
          </Stack>
        </DataSurface>

        <DataSurface>
          <SectionHeader title="凭据与探针" />
          <Box sx={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
            <DetailField label="凭据状态" value={value.credential.configured ? "已配置" : "未配置"} />
            <DetailField label="凭据版本" value={value.credential.configured ? `v${value.credential.version}` : "-"} mono />
            <DetailField label="凭据类型" value={value.credential.type || "-"} mono />
            <DetailField label="凭据到期" value={formatTime(value.credential.expiresAt, "长期")} />
            <DetailField label="探针模型" value={stringMetadata(account.metadata, "inference_probe_model") || "-"} mono />
            <DetailField label="探针时间" value={formatTime(stringMetadata(account.metadata, "inference_probe_at"))} />
          </Box>
          <Box sx={{ px: 2, py: 1.5, borderTop: 1, borderColor: "divider" }}>
            <Typography color="text.secondary" sx={{ mb: 0.5, fontSize: 11.5 }}>最近探针错误</Typography>
            <Typography sx={{ minHeight: 20, color: stringMetadata(account.metadata, "inference_probe_error") ? "error.main" : "text.secondary", fontFamily: "ui-monospace, monospace", fontSize: 12 }}>
              {stringMetadata(account.metadata, "inference_probe_error") || "无"}
            </Typography>
          </Box>
        </DataSurface>
      </Box>

      <DataSurface sx={{ mb: 2 }}>
        <SectionHeader title="账号元数据" count={metadata.length} />
        <Box sx={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
          {metadata.map(([key, entry]) => (
            <DetailField key={key} label={key} value={entry} mono />
          ))}
          {metadata.length === 0 ? (
            <Typography color="text.secondary" sx={{ px: 2, py: 3, fontSize: 12.5 }}>暂无账号元数据</Typography>
          ) : null}
        </Box>
      </DataSurface>

      <DataSurface>
        <SectionHeader title="生命周期轨迹" count={events.data?.length ?? 0} />
        <OperationEventsTable
          events={events.data ?? []}
          isFetching={events.isFetching}
          error={events.error}
          maxHeight={520}
        />
      </DataSurface>

      <Menu anchorEl={commandAnchor} open={Boolean(commandAnchor)} onClose={() => setCommandAnchor(null)}>
        {(commands.data ?? []).map((command) => (
          <MenuItem
            key={command.name}
            disabled={executeCommand.isPending}
            onClick={() => executeCommand.mutate(command.name)}
          >
            {command.displayName}
          </MenuItem>
        ))}
      </Menu>
    </PageContainer>
  );
}

function AccountDetailsSkeleton() {
  return (
    <PageContainer maxWidth={1480}>
      <Skeleton width={320} height={48} />
      <Skeleton width={220} height={24} sx={{ mb: 2 }} />
      <Skeleton variant="rectangular" height={96} sx={{ mb: 2 }} />
      <Box sx={{ display: "grid", gridTemplateColumns: "1.15fr 0.85fr", gap: 2 }}>
        <Skeleton variant="rectangular" height={360} />
        <Skeleton variant="rectangular" height={360} />
      </Box>
    </PageContainer>
  );
}

function SectionHeader({ title, count }: { title: string; count?: number }) {
  return (
    <Box sx={{ height: 44, px: 2, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
      <Typography sx={{ fontSize: 12.5, fontWeight: 750 }}>{title}</Typography>
      {count !== undefined ? <Typography color="text.secondary" sx={{ ml: 1, fontSize: 11.5 }}>{count}</Typography> : null}
    </Box>
  );
}

function SummaryCell({
  label,
  value,
  tone,
  children,
}: {
  label: string;
  value?: string;
  tone?: string;
  children?: React.ReactNode;
}) {
  return (
    <Box sx={{ px: 2.25, py: 1.75, borderRight: 1, borderColor: "divider", "&:last-child": { borderRight: 0 } }}>
      <Typography color="text.secondary" sx={{ mb: 0.75, fontSize: 11.5 }}>{label}</Typography>
      {children ?? <Typography sx={{ color: tone, fontSize: 21, fontWeight: 750, fontVariantNumeric: "tabular-nums" }}>{value}</Typography>}
    </Box>
  );
}

function DetailField({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <Box sx={{ minWidth: 0, px: 2, py: 1.4, borderBottom: 1, borderRight: 1, borderColor: "divider" }}>
      <Typography color="text.secondary" sx={{ mb: 0.4, fontSize: 11 }}>{label}</Typography>
      <Typography title={value} noWrap sx={{ fontFamily: mono ? "ui-monospace, monospace" : undefined, fontSize: 12.5 }}>{value}</Typography>
    </Box>
  );
}

function StatusValue({ status, error }: { status: string; error: string | null }) {
  const color = status === "ACTIVE" ? "success" : status === "PENDING" ? "warning" : status === "DEGRADED" ? "error" : "default";
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
      <Chip size="small" color={color} variant="outlined" label={status} />
      <Typography noWrap title={error ?? ""} color="text.secondary" sx={{ maxWidth: 220, fontSize: 11.5 }}>{error || "运行状态已同步"}</Typography>
    </Stack>
  );
}

function ReadinessValue({ value }: { value: string }) {
  const ready = value === "READY";
  return (
    <Stack direction="row" spacing={0.75} sx={{ alignItems: "center", color: ready ? "success.main" : value === "FAILED" ? "error.main" : "text.secondary" }}>
      {ready ? <CheckCircleOutlined sx={{ fontSize: 19 }} /> : <ErrorOutlineOutlined sx={{ fontSize: 19 }} />}
      <Typography sx={{ color: "inherit", fontSize: 14, fontWeight: 750 }}>{value}</Typography>
    </Stack>
  );
}

function metadataEntries(metadata: Record<string, unknown>): Array<[string, string]> {
  return Object.entries(metadata)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => [key, hiddenMetadataKey.test(key) ? "已隐藏" : formatMetadata(value)]);
}

function formatMetadata(value: unknown): string {
  if (value === null || value === undefined) return "-";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  const serialized = JSON.stringify(value);
  return serialized.length > 300 ? `${serialized.slice(0, 297)}...` : serialized;
}

function stringMetadata(metadata: Record<string, unknown>, key: string): string {
  const value = metadata[key];
  return typeof value === "string" ? value : value === undefined || value === null ? "" : String(value);
}

function formatTime(value: string | null | undefined, empty = "-") {
  return value
    ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
    : empty;
}

function formatNumber(value: number) {
  return value.toLocaleString("zh-CN");
}
