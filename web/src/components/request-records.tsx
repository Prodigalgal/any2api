"use client";

import { CheckCircleOutlined, CloseOutlined, ContentCopyOutlined, RefreshOutlined, SearchOutlined, SpeedOutlined, TerminalOutlined, VisibilityOutlined } from "@mui/icons-material";
import { Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, IconButton, LinearProgress, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Tooltip, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { api, providerOptions, type UsageEvent } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader, ToolbarSurface } from "@/components/page-layout";
import { tokens } from "@/theme/theme";

export function RequestRecords() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [kind, setKind] = useState("");
  const searchParams = useSearchParams();
  const [apiKeyId, setApiKeyId] = useState(searchParams.get("api_key_id") ?? "");
  const [model, setModel] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<UsageEvent | null>(null);
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const requests = useQuery({
    queryKey: ["request-logs", provider, model, apiKeyId, status, kind, search, page, size],
    queryFn: () => api.requestLogs({ provider, model, api_key_id: apiKeyId, status, request_kind: kind, search, page, size }),
    refetchInterval: 10_000,
  });
  const applySearch = () => { setPage(0); setSearch(searchInput.trim()); };

  return <PageContainer>
    <PageHeader title="请求记录" description="推理请求、账号路由、耗时和完整输入输出" actions={<Tooltip title="刷新记录"><IconButton onClick={() => requests.refetch()} sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}><RefreshOutlined sx={{ fontSize: 18 }} /></IconButton></Tooltip>} />
    <ToolbarSurface>
      <TextField select size="small" label="厂商" value={provider} onChange={(event) => { setProvider(event.target.value); setPage(0); }} sx={{ width: 180 }}><MenuItem value="">全部厂商</MenuItem>{providerOptions(catalog.data).map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}</TextField>
      <TextField select size="small" label="状态" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }} sx={{ width: 150 }}><MenuItem value="">全部状态</MenuItem><MenuItem value="SUCCEEDED">成功</MenuItem><MenuItem value="FAILED">失败</MenuItem></TextField>
      <TextField select size="small" label="请求类型" value={kind} onChange={(event) => { setKind(event.target.value); setPage(0); }} sx={{ width: 160 }}><MenuItem value="">全部类型</MenuItem><MenuItem value="INFERENCE">推理</MenuItem><MenuItem value="PROBE">真实探针</MenuItem></TextField>
      <TextField size="small" label="模型" value={model} onChange={(event) => { setModel(event.target.value); setPage(0); }} sx={{ width: 190 }} />
      <TextField size="small" label="密钥 ID" value={apiKeyId} onChange={(event) => { setApiKeyId(event.target.value); setPage(0); }} sx={{ width: 220 }} />
      <TextField size="small" label="请求 ID / 模型 / 账号 / 错误" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") applySearch(); }} sx={{ width: 340 }} />
      <Button variant="outlined" startIcon={<SearchOutlined />} onClick={applySearch}>查询</Button>
    </ToolbarSurface>
    {requests.error ? <Alert severity="error" sx={{ mb: 2 }}>{requests.error.message}</Alert> : null}
    <DataSurface>
      {requests.isFetching ? <LinearProgress sx={{ height: 2 }} /> : null}
      <TableContainer sx={{ minHeight: 420, maxHeight: "calc(100vh - 300px)" }}><Table stickyHeader size="small" sx={{ minWidth: 1250 }}>
        <TableHead><TableRow><TableCell>状态</TableCell><TableCell>厂商</TableCell><TableCell>模型 / 协议</TableCell><TableCell>Request ID</TableCell><TableCell>账号 / 密钥</TableCell><TableCell align="right">Token</TableCell><TableCell align="right">阶段耗时</TableCell><TableCell>时间</TableCell><TableCell align="right">详情</TableCell></TableRow></TableHead>
        <TableBody>{(requests.data?.items ?? []).map((item) => <TableRow key={`${item.requestId}:${item.attempt}`} hover onDoubleClick={() => setSelected(item)}>
          <TableCell><Chip size="small" variant="outlined" color={item.success ? "success" : "error"} label={item.success ? "成功" : item.errorClass || "失败"} /></TableCell>
          <TableCell><Chip size="small" variant="outlined" label={item.providerId} /></TableCell>
          <TableCell><Typography noWrap sx={mono}>{item.modelId}</Typography><Typography color="text.secondary" sx={subtle}>{item.protocol} · {item.requestKind} · attempt {item.attempt}</Typography></TableCell>
          <TableCell><Identifier value={item.requestId} /></TableCell>
          <TableCell><Identifier value={item.accountId} /><Typography color="text.secondary" sx={subtle}>key {short(item.apiKeyId)}</Typography></TableCell>
          <TableCell align="right"><Typography sx={mono}>{item.inputTokens} / {item.outputTokens}</Typography><Typography color="text.secondary" sx={subtle}>{item.usageSource} · cache {item.cacheReadTokens}</Typography></TableCell>
          <TableCell align="right"><Tooltip title={`排队 ${duration(item.queueMs)} · 取号 ${duration(item.accountAcquireMs)} · TTFB ${duration(item.ttfbMs)} · 生成 ${duration(item.generationMs)}`}><Typography sx={mono}>{duration(item.durationMs)}</Typography></Tooltip></TableCell>
          <TableCell sx={{ fontSize: 11.5 }}>{formatTime(item.createdAt)}</TableCell>
          <TableCell align="right"><Tooltip title="查看全链路时延与数据详情"><IconButton size="small" onClick={() => setSelected(item)}><VisibilityOutlined sx={{ fontSize: 18 }} /></IconButton></Tooltip></TableCell>
        </TableRow>)}{!requests.isLoading && (requests.data?.items.length ?? 0) === 0 ? <TableRow><TableCell colSpan={9} align="center" sx={{ py: 8, color: "text.secondary" }}>没有符合条件的请求</TableCell></TableRow> : null}</TableBody>
      </Table></TableContainer>
      <TablePagination component="div" count={requests.data?.totalElements ?? 0} page={page} rowsPerPage={size} rowsPerPageOptions={[20, 50, 100]} onPageChange={(_, value) => setPage(value)} onRowsPerPageChange={(event) => { setSize(Number(event.target.value)); setPage(0); }} labelRowsPerPage="每页" />
    </DataSurface>
    {selected ? <RequestDetailDialog request={selected} onClose={() => setSelected(null)} /> : null}
  </PageContainer>;
}

function RequestDetailDialog({ request, onClose }: { request: UsageEvent; onClose: () => void }) {
  const detail = useQuery({ queryKey: ["request-log-detail", request.requestId, request.attempt], queryFn: () => api.requestLogDetail(request.requestId, request.attempt) });
  const [copiedCurl, setCopiedCurl] = useState(false);
  const [copiedId, setCopiedId] = useState(false);

  const total = Math.max(1, request.durationMs);
  const qPct = Math.max(0, Math.min(100, (request.queueMs / total) * 100));
  const acqPct = Math.max(0, Math.min(100, (request.accountAcquireMs / total) * 100));
  const ttfbPct = Math.max(0, Math.min(100, (request.ttfbMs / total) * 100));
  const genPct = Math.max(0, Math.min(100, (request.generationMs / total) * 100));

  const copyCurl = () => {
    const input = detail.data?.input;
    const bodyStr = input ? JSON.stringify(input, null, 2) : JSON.stringify({ model: request.modelId, messages: [{ role: "user", content: "Hello" }] }, null, 2);
    const curl = `curl -X POST "http://localhost:8080/v1/chat/completions" \\\n  -H "Content-Type: application/json" \\\n  -H "Authorization: Bearer <API_KEY>" \\\n  -d '${bodyStr.replace(/'/g, "'\\''")}'`;
    navigator.clipboard.writeText(curl);
    setCopiedCurl(true);
    setTimeout(() => setCopiedCurl(false), 2000);
  };

  const copyId = () => {
    navigator.clipboard.writeText(request.requestId);
    setCopiedId(true);
    setTimeout(() => setCopiedId(false), 2000);
  };

  return <Dialog open onClose={onClose} maxWidth="xl" fullWidth slotProps={{ paper: { sx: { bgcolor: tokens.canvas, border: `1px solid ${tokens.border}`, borderRadius: "16px", backgroundImage: "none" } } }}>
    <DialogTitle sx={{ display: "flex", alignItems: "center", py: 2, px: 3, borderBottom: `1px solid ${tokens.border}`, bgcolor: tokens.canvasSubtle }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, minWidth: 0 }}>
        <Box sx={{ width: 36, height: 36, borderRadius: "10px", bgcolor: request.success ? "rgba(16, 185, 129, 0.12)" : "rgba(244, 63, 94, 0.12)", border: `1px solid ${request.success ? "rgba(16, 185, 129, 0.25)" : "rgba(244, 63, 94, 0.25)"}`, display: "flex", alignItems: "center", justifyContent: "center" }}>
          {request.success ? <CheckCircleOutlined sx={{ fontSize: 20, color: tokens.status.emerald }} /> : <SpeedOutlined sx={{ fontSize: 20, color: tokens.status.rose }} />}
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Typography sx={{ fontSize: 15, fontWeight: 700, color: tokens.text.primary }}>全链路请求追踪 (Trace Inspector)</Typography>
            <Chip size="small" variant="outlined" color={request.success ? "success" : "error"} label={request.success ? "成功 200" : (request.errorClass || "失败")} sx={{ height: 20, fontSize: 10.5 }} />
            <Chip size="small" label={`Attempt ${request.attempt}`} sx={{ height: 20, fontSize: 10.5, bgcolor: tokens.surface, color: tokens.text.secondary }} />
          </Box>
          <Typography noWrap color="text.secondary" sx={{ ...mono, fontSize: 11, mt: 0.25 }}>ID: {request.requestId}</Typography>
        </Box>
      </Box>
      <Box sx={{ flex: 1 }} />
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        <Button size="small" variant="outlined" startIcon={<TerminalOutlined sx={{ fontSize: 16 }} />} onClick={copyCurl} sx={{ height: 32, fontSize: 11.5, borderColor: tokens.border, color: tokens.text.secondary }}>
          {copiedCurl ? "已复制 cURL" : "复制 cURL"}
        </Button>
        <Button size="small" variant="outlined" startIcon={<ContentCopyOutlined sx={{ fontSize: 16 }} />} onClick={copyId} sx={{ height: 32, fontSize: 11.5, borderColor: tokens.border, color: tokens.text.secondary }}>
          {copiedId ? "已复制 ID" : "复制 ID"}
        </Button>
        <IconButton onClick={onClose} sx={{ color: tokens.text.secondary }}><CloseOutlined sx={{ fontSize: 18 }} /></IconButton>
      </Box>
    </DialogTitle>

    <DialogContent sx={{ p: 3 }}>
      {/* Waterfall Timeline */}
      <Box sx={{ mb: 3, p: 2.5, bgcolor: tokens.canvasSubtle, border: `1px solid ${tokens.border}`, borderRadius: "12px" }}>
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 1.5 }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <SpeedOutlined sx={{ fontSize: 18, color: tokens.primary.main }} />
            <Typography sx={{ fontSize: 13, fontWeight: 700, color: tokens.text.primary }}>耗时瀑布流 (Timeline Waterfall)</Typography>
          </Box>
          <Typography sx={{ ...mono, fontSize: 13, fontWeight: 700, color: tokens.text.primary }}>总耗时: {duration(request.durationMs)}</Typography>
        </Box>

        {/* Progress bar */}
        <Box sx={{ height: 10, borderRadius: "5px", overflow: "hidden", display: "flex", bgcolor: tokens.surface, mb: 2, border: `1px solid ${tokens.border}` }}>
          {qPct > 0 ? <Tooltip title={`排队: ${request.queueMs} ms (${qPct.toFixed(1)}%)`}><Box sx={{ width: `${qPct}%`, bgcolor: "#a855f7" }} /></Tooltip> : null}
          {acqPct > 0 ? <Tooltip title={`取号: ${request.accountAcquireMs} ms (${acqPct.toFixed(1)}%)`}><Box sx={{ width: `${acqPct}%`, bgcolor: "#f59e0b" }} /></Tooltip> : null}
          {ttfbPct > 0 ? <Tooltip title={`TTFB: ${request.ttfbMs} ms (${ttfbPct.toFixed(1)}%)`}><Box sx={{ width: `${ttfbPct}%`, bgcolor: "#38bdf8" }} /></Tooltip> : null}
          {genPct > 0 ? <Tooltip title={`生成: ${request.generationMs} ms (${genPct.toFixed(1)}%)`}><Box sx={{ width: `${genPct}%`, bgcolor: "#10b981" }} /></Tooltip> : null}
        </Box>

        {/* Breakdown stage cards */}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr 1fr", sm: "repeat(4, 1fr)" }, gap: 1.5 }}>
          <StageCard label="并发排队 (Queue)" value={`${request.queueMs} ms`} pct={`${qPct.toFixed(1)}%`} color="#a855f7" />
          <StageCard label="凭据租约 (Acquire)" value={`${request.accountAcquireMs} ms`} pct={`${acqPct.toFixed(1)}%`} color="#f59e0b" />
          <StageCard label="首字节 (TTFB)" value={`${request.ttfbMs} ms`} pct={`${ttfbPct.toFixed(1)}%`} color="#38bdf8" />
          <StageCard label="流式传输 (Generation)" value={`${request.generationMs} ms`} pct={`${genPct.toFixed(1)}%`} color="#10b981" />
        </Box>

        {/* Metadata summary bar */}
        <Box sx={{ mt: 2, pt: 1.5, borderTop: `1px dashed ${tokens.border}`, display: "flex", flexWrap: "wrap", gap: 1, alignItems: "center" }}>
          <MetaPill label="厂商" value={request.providerId} />
          <MetaPill label="模型" value={request.modelId} highlight />
          <MetaPill label="协议" value={request.protocol} />
          <MetaPill label="类型" value={request.requestKind} />
          <MetaPill label="输入/输出" value={`${request.inputTokens} / ${request.outputTokens} t`} />
          <MetaPill label="缓存命中" value={`${request.cacheReadTokens} t`} />
          <MetaPill label="账号" value={short(request.accountId)} />
        </Box>
      </Box>

      {/* Payloads Inspector */}
      {detail.isLoading ? <LinearProgress sx={{ borderRadius: 1 }} /> : null}
      {detail.error ? <Alert severity="error" sx={{ mb: 2 }}>{detail.error.message}</Alert> : null}
      {detail.data ? (
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, gap: 2, minHeight: 520 }}>
          <JsonPane title="请求输入 (Request Input)" value={detail.data.input} />
          <JsonPane title="响应输出 (Response Output)" value={detail.data.output} />
        </Box>
      ) : null}
    </DialogContent>
  </Dialog>;
}

function StageCard({ label, value, pct, color }: { label: string; value: string; pct: string; color: string }) {
  return <Box sx={{ p: 1.25, bgcolor: tokens.surface, borderRadius: "8px", border: `1px solid ${tokens.border}` }}>
    <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, mb: 0.5 }}>
      <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: color }} />
      <Typography sx={{ fontSize: 11, color: tokens.text.secondary }}>{label}</Typography>
    </Box>
    <Box sx={{ display: "flex", alignItems: "baseline", justifyContent: "space-between" }}>
      <Typography sx={{ ...mono, fontSize: 12.5, fontWeight: 700, color: tokens.text.primary }}>{value}</Typography>
      <Typography sx={{ ...mono, fontSize: 10.5, color: tokens.text.muted }}>{pct}</Typography>
    </Box>
  </Box>;
}

function MetaPill({ label, value, highlight = false }: { label: string; value: string; highlight?: boolean }) {
  return <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.5, px: 1, py: 0.25, bgcolor: tokens.surface, borderRadius: "6px", border: `1px solid ${tokens.border}`, fontSize: 11 }}>
    <Typography component="span" sx={{ fontSize: 10.5, color: tokens.text.muted }}>{label}:</Typography>
    <Typography component="span" sx={{ ...mono, fontSize: 11, fontWeight: highlight ? 700 : 500, color: highlight ? tokens.primary.main : tokens.text.primary }}>{value}</Typography>
  </Box>;
}

function JsonPane({ title, value }: { title: string; value: unknown }) {
  const [copied, setCopied] = useState(false);
  const text = JSON.stringify(value, null, 2);
  const copy = () => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return <Box sx={{ minWidth: 0, bgcolor: tokens.canvasSubtle, border: `1px solid ${tokens.border}`, borderRadius: "10px", display: "flex", flexDirection: "column" }}>
    <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${tokens.border}`, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
      <Typography sx={{ fontSize: 11.5, fontWeight: 700, color: tokens.text.secondary }}>{title}</Typography>
      <Button size="small" variant="text" startIcon={<ContentCopyOutlined sx={{ fontSize: 14 }} />} onClick={copy} sx={{ minWidth: 0, p: "2px 8px", fontSize: 10.5, color: tokens.text.secondary }}>
        {copied ? "已复制" : "复制 JSON"}
      </Button>
    </Box>
    <Box component="pre" sx={{ m: 0, p: 2, height: 480, overflow: "auto", whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontFamily: "ui-monospace, monospace", fontSize: 11.5, lineHeight: 1.6, bgcolor: tokens.canvas, color: tokens.text.primary }}>
      {text}
    </Box>
  </Box>;
}

const mono = { fontFamily: "ui-monospace, monospace" } as const;
const subtle = { fontSize: 10.5 } as const;
function short(value: string | null) { return value ? value.slice(0, 12) : "system"; }
function Identifier({ value }: { value: string | null }) { return <Tooltip title={value || "system"}><Typography noWrap sx={{ ...mono, fontSize: 11.5 }}>{short(value)}</Typography></Tooltip>; }
function duration(value: number) { return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`; }
function formatTime(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value)); }
