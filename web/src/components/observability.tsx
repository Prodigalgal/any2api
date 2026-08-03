"use client";

import {
  Alert,
  Box,
  Chip,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

export function Observability() {
  const summary = useQuery({
    queryKey: ["observability-summary"],
    queryFn: api.observabilitySummary,
    refetchInterval: 10_000,
  });
  const usage = useQuery({
    queryKey: ["usage-events", 100],
    queryFn: () => api.usageEvents(100),
    refetchInterval: 10_000,
  });
  const error = summary.error ?? usage.error;
  const successRate = summary.data?.requestCount
    ? (summary.data.successCount / summary.data.requestCount) * 100
    : 0;

  return (
    <PageContainer>
      <PageHeader title="运行观测" description="推理、注册与账号生命周期的统一运行轨迹" />
      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}

      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(5, minmax(0, 1fr))", gap: 1.5, mb: 2 }}>
        <Metric label="24 小时请求" value={formatNumber(summary.data?.requestCount)} />
        <Metric label="成功率" value={`${successRate.toFixed(2)}%`} tone={successRate >= 95 ? "success.main" : "warning.main"} />
        <Metric label="失败请求" value={formatNumber(summary.data?.failureCount)} tone={summary.data?.failureCount ? "error.main" : undefined} />
        <Metric label="P95 响应" value={formatDuration(summary.data?.p95DurationMs ?? 0)} />
        <Metric label="执行中任务" value={formatNumber(summary.data?.runningOperations)} tone={summary.data?.runningOperations ? "primary.main" : undefined} />
      </Box>

      <DataSurface sx={{ mb: 2 }}>
        <SectionTitle title="24 小时自动化失败" subtitle="按厂商、操作、阶段和错误码聚合" />
        <TableContainer sx={{ maxHeight: 260 }}>
          <Table stickyHeader size="small">
            <TableHead><TableRow>
              <TableCell>厂商</TableCell><TableCell>操作</TableCell><TableCell>阶段</TableCell>
              <TableCell>错误码</TableCell><TableCell align="right">次数</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {(summary.data?.operationFailures ?? []).map((failure) => (
                <TableRow key={`${failure.providerId}:${failure.operation}:${failure.stage}:${failure.errorCode}`} hover>
                  <TableCell><Chip size="small" variant="outlined" label={failure.providerId} /></TableCell>
                  <TableCell sx={mono}>{failure.operation}</TableCell>
                  <TableCell sx={mono}>{failure.stage}</TableCell>
                  <TableCell sx={{ ...mono, color: "error.main" }}>{failure.errorCode || "operation_failed"}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>{failure.count}</TableCell>
                </TableRow>
              ))}
              {!summary.isLoading && (summary.data?.operationFailures.length ?? 0) === 0 ? (
                <TableRow><TableCell colSpan={5} align="center" sx={{ py: 4, color: "text.secondary" }}>当前时间窗没有自动化失败</TableCell></TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
      </DataSurface>

      <DataSurface>
        {(summary.isFetching || usage.isFetching) ? <LinearProgress sx={{ height: 2 }} /> : null}
        <SectionTitle title="最近推理请求" subtitle="每次重试单独记录，关联 ID 保持一致" />
        <TableContainer sx={{ height: "calc(100vh - 620px)", minHeight: 300, maxHeight: 560 }}>
          <Table stickyHeader size="small" sx={{ minWidth: 1180 }}>
            <TableHead><TableRow>
              <TableCell sx={{ width: 115 }}>状态</TableCell><TableCell sx={{ width: 110 }}>厂商</TableCell>
              <TableCell sx={{ width: 220 }}>模型 / 协议</TableCell><TableCell>关联 ID</TableCell>
              <TableCell sx={{ width: 155 }}>账号</TableCell><TableCell sx={{ width: 155 }}>分发密钥</TableCell>
              <TableCell sx={{ width: 125 }} align="right">输入 / 输出</TableCell><TableCell sx={{ width: 90 }} align="right">耗时</TableCell>
              <TableCell sx={{ width: 150 }}>时间</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {(usage.data ?? []).map((event) => (
                <TableRow key={event.requestId} hover>
                  <TableCell><Chip size="small" variant="outlined" color={event.success ? "success" : "error"} label={event.success ? "SUCCEEDED" : event.errorClass || "FAILED"} /></TableCell>
                  <TableCell><Chip size="small" variant="outlined" label={event.providerId} /></TableCell>
                  <TableCell>
                    <Typography noWrap sx={{ ...mono, color: "text.primary" }}>{event.modelId}</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{event.protocol}</Typography>
                  </TableCell>
                  <TableCell><Identifier value={event.requestId.replace(/:\d+$/, "")} /></TableCell>
                  <TableCell><Identifier value={event.accountId} /></TableCell>
                  <TableCell><Identifier value={event.apiKeyId} /></TableCell>
                  <TableCell align="right" sx={mono}>{event.inputTokens} / {event.outputTokens}</TableCell>
                  <TableCell align="right" sx={mono}>{formatDuration(event.durationMs)}</TableCell>
                  <TableCell sx={{ fontSize: 11.5 }}>{formatTime(event.createdAt)}</TableCell>
                </TableRow>
              ))}
              {!usage.isLoading && (usage.data?.length ?? 0) === 0 ? (
                <TableRow><TableCell colSpan={9} align="center" sx={{ py: 8, color: "text.secondary" }}>暂无推理事件</TableCell></TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
      </DataSurface>
    </PageContainer>
  );
}

const mono = { fontFamily: "ui-monospace, monospace", fontSize: 11.5 } as const;

function Metric({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return <DataSurface sx={{ px: 2, py: 1.75 }}>
    <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{label}</Typography>
    <Typography sx={{ mt: 0.5, fontSize: 21, lineHeight: 1.2, fontWeight: 750, fontVariantNumeric: "tabular-nums", color: tone }}>{value}</Typography>
  </DataSurface>;
}

function SectionTitle({ title, subtitle }: { title: string; subtitle: string }) {
  return <Stack direction="row" sx={{ px: 2, height: 48, alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
    <Typography sx={{ fontSize: 12.5, fontWeight: 750 }}>{title}</Typography>
    <Typography color="text.secondary" sx={{ ml: 1, fontSize: 11.5 }}>{subtitle}</Typography>
  </Stack>;
}

function Identifier({ value }: { value: string | null }) {
  if (!value) return <Typography color="text.secondary" sx={mono}>system</Typography>;
  return <Tooltip title={value} placement="left"><Typography noWrap sx={mono}>{value.slice(0, 12)}</Typography></Tooltip>;
}

function formatNumber(value?: number) { return (value ?? 0).toLocaleString("zh-CN"); }
function formatDuration(value: number) { return value < 1_000 ? `${value} ms` : `${(value / 1_000).toFixed(1)} s`; }
function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value));
}
