"use client";

import { CloseOutlined, RefreshOutlined, SearchOutlined, VisibilityOutlined } from "@mui/icons-material";
import { Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, IconButton, LinearProgress, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Tooltip, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { api, providerOptions, type UsageEvent } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader, ToolbarSurface } from "@/components/page-layout";

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
          <TableCell><Chip size="small" variant="outlined" color={item.success ? "success" : "error"} label={item.success ? "SUCCEEDED" : item.errorClass || "FAILED"} /></TableCell>
          <TableCell><Chip size="small" variant="outlined" label={item.providerId} /></TableCell>
          <TableCell><Typography noWrap sx={mono}>{item.modelId}</Typography><Typography color="text.secondary" sx={subtle}>{item.protocol} · {item.requestKind} · attempt {item.attempt}</Typography></TableCell>
          <TableCell><Identifier value={item.requestId} /></TableCell>
          <TableCell><Identifier value={item.accountId} /><Typography color="text.secondary" sx={subtle}>key {short(item.apiKeyId)}</Typography></TableCell>
          <TableCell align="right"><Typography sx={mono}>{item.inputTokens} / {item.outputTokens}</Typography><Typography color="text.secondary" sx={subtle}>{item.usageSource} · cache {item.cacheReadTokens}</Typography></TableCell>
          <TableCell align="right"><Tooltip title={`排队 ${duration(item.queueMs)} · 取号 ${duration(item.accountAcquireMs)} · TTFB ${duration(item.ttfbMs)} · 生成 ${duration(item.generationMs)}`}><Typography sx={mono}>{duration(item.durationMs)}</Typography></Tooltip></TableCell>
          <TableCell sx={{ fontSize: 11.5 }}>{formatTime(item.createdAt)}</TableCell>
          <TableCell align="right"><Tooltip title="查看完整输入输出"><IconButton size="small" onClick={() => setSelected(item)}><VisibilityOutlined sx={{ fontSize: 18 }} /></IconButton></Tooltip></TableCell>
        </TableRow>)}{!requests.isLoading && (requests.data?.items.length ?? 0) === 0 ? <TableRow><TableCell colSpan={9} align="center" sx={{ py: 8, color: "text.secondary" }}>没有符合条件的请求</TableCell></TableRow> : null}</TableBody>
      </Table></TableContainer>
      <TablePagination component="div" count={requests.data?.totalElements ?? 0} page={page} rowsPerPage={size} rowsPerPageOptions={[20, 50, 100]} onPageChange={(_, value) => setPage(value)} onRowsPerPageChange={(event) => { setSize(Number(event.target.value)); setPage(0); }} labelRowsPerPage="每页" />
    </DataSurface>
    {selected ? <RequestDetailDialog request={selected} onClose={() => setSelected(null)} /> : null}
  </PageContainer>;
}

function RequestDetailDialog({ request, onClose }: { request: UsageEvent; onClose: () => void }) {
  const detail = useQuery({ queryKey: ["request-log-detail", request.requestId, request.attempt], queryFn: () => api.requestLogDetail(request.requestId, request.attempt) });
  return <Dialog open onClose={onClose} maxWidth="xl" fullWidth>
    <DialogTitle sx={{ display: "flex", alignItems: "center", py: 1.5 }}><Box sx={{ minWidth: 0 }}><Typography sx={{ fontSize: 14, fontWeight: 750 }}>请求内容</Typography><Typography noWrap color="text.secondary" sx={mono}>{request.requestId} · attempt {request.attempt}</Typography></Box><Box sx={{ flex: 1 }} /><IconButton onClick={onClose}><CloseOutlined /></IconButton></DialogTitle>
    <DialogContent dividers sx={{ p: 0 }}>{detail.isLoading ? <LinearProgress /> : null}{detail.error ? <Alert severity="error" sx={{ m: 2 }}>{detail.error.message}</Alert> : null}{detail.data ? <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "1fr 1fr" }, minHeight: 620 }}><JsonPane title="INPUT" value={detail.data.input} /><JsonPane title="OUTPUT" value={detail.data.output} border /></Box> : null}</DialogContent>
  </Dialog>;
}

function JsonPane({ title, value, border = false }: { title: string; value: unknown; border?: boolean }) { return <Box sx={{ minWidth: 0, borderLeft: border ? 1 : 0, borderColor: "divider" }}><Typography sx={{ px: 2, py: 1.25, borderBottom: 1, borderColor: "divider", fontSize: 10.5, fontWeight: 800, color: "text.secondary" }}>{title}</Typography><Box component="pre" sx={{ m: 0, p: 2, height: 570, overflow: "auto", whiteSpace: "pre-wrap", overflowWrap: "anywhere", fontFamily: "ui-monospace, monospace", fontSize: 11.5, lineHeight: 1.65 }}>{JSON.stringify(value, null, 2)}</Box></Box>; }
const mono = { fontFamily: "ui-monospace, monospace", fontSize: 11.5 } as const;
const subtle = { fontSize: 10.5 } as const;
function short(value: string | null) { return value ? value.slice(0, 12) : "system"; }
function Identifier({ value }: { value: string | null }) { return <Tooltip title={value || "system"}><Typography noWrap sx={mono}>{short(value)}</Typography></Tooltip>; }
function duration(value: number) { return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`; }
function formatTime(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value)); }
