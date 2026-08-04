"use client";

import { ArrowBackOutlined, OpenInNewOutlined } from "@mui/icons-material";
import { Alert, Box, Button, Chip, LinearProgress, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { api, type ApiKeyUsageWindow } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

export function ApiKeyDetailPage({ id }: { id: string }) {
  const detail = useQuery({ queryKey: ["api-key-detail", id], queryFn: () => api.apiKeyDetail(id), refetchInterval: 15_000 });
  return <PageContainer>
    <PageHeader title={detail.data?.key.name ?? "密钥详情"} description={detail.data ? `${detail.data.key.prefix}... · 创建于 ${formatTime(detail.data.key.createdAt)}` : "使用范围与调用统计"} actions={<Button component={Link} href="/api-keys" startIcon={<ArrowBackOutlined />}>返回密钥列表</Button>} />
    {detail.isLoading ? <LinearProgress /> : null}
    {detail.error ? <Alert severity="error">{detail.error.message}</Alert> : null}
    {detail.data ? <Stack spacing={2}>
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 1.5 }}>
        <UsageCard label="24 小时" value={detail.data.last24Hours} />
        <UsageCard label="7 天" value={detail.data.last7Days} />
        <UsageCard label="30 天" value={detail.data.last30Days} />
        <UsageCard label="全部时间" value={detail.data.lifetime} />
      </Box>
      <DataSurface>
        <SectionHeader title="授权范围" />
        <Box sx={{ p: 2, display: "grid", gridTemplateColumns: "1.4fr 1fr 1fr", gap: 2 }}>
          <Box><Label>厂商与模型</Label><Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap" }}>{Object.entries(detail.data.key.providerModels).map(([provider, models]) => <Chip key={provider} size="small" variant="outlined" label={`${provider} · ${models.length ? `${models.length} 模型` : "全部模型"}`} />)}</Stack></Box>
          <Box><Label>协议</Label><Typography sx={{ fontSize: 12 }}>{detail.data.key.protocols.join(" · ")}</Typography></Box>
          <Box><Label>功能</Label><Typography sx={{ fontSize: 12 }}>{detail.data.key.features.length ? detail.data.key.features.join(" · ") : "仅文本"}</Typography></Box>
        </Box>
      </DataSurface>
      <DataSurface>
        <SectionHeader title="近 30 天模型使用" action={<Button component={Link} href={`/requests?api_key_id=${encodeURIComponent(id)}`} endIcon={<OpenInNewOutlined />} size="small">查看请求记录</Button>} />
        <TableContainer><Table size="small"><TableHead><TableRow><TableCell>厂商</TableCell><TableCell>模型</TableCell><TableCell align="right">请求</TableCell><TableCell align="right">成功率</TableCell><TableCell align="right">输入 / 输出 Token</TableCell><TableCell align="right">P95</TableCell><TableCell>最后使用</TableCell></TableRow></TableHead>
          <TableBody>{detail.data.modelUsage.map((item) => <TableRow key={`${item.providerId}:${item.modelId}`} hover><TableCell><Chip size="small" variant="outlined" label={item.providerId} /></TableCell><TableCell sx={mono}>{item.modelId}</TableCell><TableCell align="right">{number(item.requestCount)}</TableCell><TableCell align="right">{rate(item.successCount, item.requestCount)}</TableCell><TableCell align="right" sx={mono}>{number(item.inputTokens)} / {number(item.outputTokens)}</TableCell><TableCell align="right" sx={mono}>{duration(item.p95DurationMs)}</TableCell><TableCell>{formatTime(item.lastUsedAt)}</TableCell></TableRow>)}{detail.data.modelUsage.length === 0 ? <TableRow><TableCell colSpan={7} align="center" sx={{ py: 6, color: "text.secondary" }}>该密钥尚无请求记录</TableCell></TableRow> : null}</TableBody>
        </Table></TableContainer>
      </DataSurface>
    </Stack> : null}
  </PageContainer>;
}

function UsageCard({ label, value }: { label: string; value: ApiKeyUsageWindow }) { return <DataSurface sx={{ p: 2 }}><Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{label}</Typography><Stack direction="row" sx={{ alignItems: "baseline", mt: 0.5 }}><Typography sx={{ fontSize: 22, fontWeight: 750 }}>{number(value.requestCount)}</Typography><Typography color="text.secondary" sx={{ ml: 0.75, fontSize: 11 }}>请求 · {number(value.attemptCount)} 次上游尝试</Typography></Stack><Typography sx={{ mt: 1, fontSize: 11.5 }}>成功率 {rate(value.successCount, value.requestCount)} · P95 {duration(value.p95DurationMs)}</Typography><Typography color="text.secondary" sx={{ mt: 0.35, fontSize: 10.5 }}>Token {number(value.inputTokens)} / {number(value.outputTokens)}</Typography></DataSurface>; }
function SectionHeader({ title, action }: { title: string; action?: React.ReactNode }) { return <Box sx={{ height: 48, px: 2, borderBottom: 1, borderColor: "divider", display: "flex", alignItems: "center" }}><Typography sx={{ fontSize: 12.5, fontWeight: 750 }}>{title}</Typography><Box sx={{ flex: 1 }} />{action}</Box>; }
function Label({ children }: { children: React.ReactNode }) { return <Typography color="text.secondary" sx={{ mb: 0.75, fontSize: 10.5, fontWeight: 700 }}>{children}</Typography>; }
const mono = { fontFamily: "ui-monospace, monospace", fontSize: 11.5 } as const;
function number(value: number) { return value.toLocaleString("zh-CN"); }
function rate(success: number, total: number) { return total ? `${((success / total) * 100).toFixed(2)}%` : "-"; }
function duration(value: number) { return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`; }
function formatTime(value: string | null) { return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "-"; }
