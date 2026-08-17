"use client";

import { RefreshOutlined, SearchOutlined } from "@mui/icons-material";
import { Alert, Button, Chip, IconButton, LinearProgress, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Tooltip, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, providerOptions } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader, ToolbarSurface } from "@/components/page-layout";

export function OperationRecords() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [provider, setProvider] = useState("");
  const [domain, setDomain] = useState("");
  const [status, setStatus] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const operations = useQuery({
    queryKey: ["operation-logs", provider, domain, status, search, page, size],
    queryFn: () => api.operationLogs({ provider, domain, status, search, page, size }),
    refetchInterval: 10_000,
  });
  const applySearch = () => { setPage(0); setSearch(searchInput.trim()); };

  return <PageContainer>
    <PageHeader title="操作记录" description="注册、保活、重授权和账号操作的执行轨迹" actions={<Tooltip title="刷新记录"><IconButton onClick={() => operations.refetch()} sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}><RefreshOutlined sx={{ fontSize: 18 }} /></IconButton></Tooltip>} />
    <ToolbarSurface>
      <TextField select size="small" label="厂商" value={provider} onChange={(event) => { setProvider(event.target.value); setPage(0); }} sx={{ width: 180 }}><MenuItem value="">全部厂商</MenuItem>{providerOptions(catalog.data).map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}</TextField>
      <TextField select size="small" label="操作域" value={domain} onChange={(event) => { setDomain(event.target.value); setPage(0); }} sx={{ width: 160 }}><MenuItem value="">全部操作域</MenuItem><MenuItem value="REGISTRATION">注册</MenuItem><MenuItem value="LIFECYCLE">生命周期</MenuItem><MenuItem value="INFERENCE">推理操作</MenuItem></TextField>
      <TextField select size="small" label="状态" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }} sx={{ width: 150 }}><MenuItem value="">全部状态</MenuItem><MenuItem value="RUNNING">执行中</MenuItem><MenuItem value="SUCCEEDED">成功</MenuItem><MenuItem value="FAILED">失败</MenuItem></TextField>
      <TextField size="small" label="关联 ID / 账号 / 错误" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") applySearch(); }} sx={{ width: 340 }} />
      <Button variant="outlined" startIcon={<SearchOutlined />} onClick={applySearch}>查询</Button>
    </ToolbarSurface>
    {operations.error ? <Alert severity="error" sx={{ mb: 2 }}>{operations.error.message}</Alert> : null}
    <DataSurface>
      {operations.isFetching ? <LinearProgress sx={{ height: 2 }} /> : null}
      <TableContainer sx={{ minHeight: 420, maxHeight: "calc(100vh - 300px)" }}><Table stickyHeader size="small" sx={{ minWidth: 1200 }}>
        <TableHead><TableRow><TableCell>状态</TableCell><TableCell>厂商 / 操作</TableCell><TableCell>阶段</TableCell><TableCell>关联 ID</TableCell><TableCell>聚合对象</TableCell><TableCell>账号</TableCell><TableCell>错误</TableCell><TableCell align="right">耗时</TableCell><TableCell>时间</TableCell></TableRow></TableHead>
        <TableBody>{(operations.data?.items ?? []).map((item) => <TableRow key={item.id} hover>
          <TableCell><Chip size="small" variant="outlined" color={item.status === "SUCCEEDED" ? "success" : item.status === "FAILED" ? "error" : "primary"} label={statusLabel(item.status)} /></TableCell>
          <TableCell><Typography sx={{ fontSize: 12, fontWeight: 700 }}>{item.providerId}</Typography><Typography color="text.secondary" sx={mono}>{domainLabel(item.domain)} · {operationLabel(item.operation)}</Typography></TableCell>
          <TableCell sx={mono}>{item.stage}</TableCell><TableCell><Identifier value={item.correlationId} /></TableCell>
          <TableCell><Typography sx={mono}>{item.aggregateType}</Typography><Identifier value={item.aggregateId} /></TableCell><TableCell><Identifier value={item.accountId} /></TableCell>
          <TableCell><Tooltip title={item.errorDetail || ""}><Typography noWrap sx={{ ...mono, maxWidth: 260, color: item.errorCode ? "error.main" : "text.secondary" }}>{item.errorCode || item.errorDetail || "-"}</Typography></Tooltip></TableCell>
          <TableCell align="right" sx={mono}>{duration(item.durationMs)}</TableCell><TableCell sx={{ fontSize: 11.5 }}>{formatTime(item.startedAt)}</TableCell>
        </TableRow>)}{!operations.isLoading && (operations.data?.items.length ?? 0) === 0 ? <TableRow><TableCell colSpan={9} align="center" sx={{ py: 8, color: "text.secondary" }}>没有符合条件的操作</TableCell></TableRow> : null}</TableBody>
      </Table></TableContainer>
      <TablePagination component="div" count={operations.data?.totalElements ?? 0} page={page} rowsPerPage={size} rowsPerPageOptions={[20, 50, 100]} onPageChange={(_, value) => setPage(value)} onRowsPerPageChange={(event) => { setSize(Number(event.target.value)); setPage(0); }} labelRowsPerPage="每页" />
    </DataSurface>
  </PageContainer>;
}

const mono = { fontFamily: "ui-monospace, monospace", fontSize: 11.5 } as const;
function Identifier({ value }: { value: string | null }) { return <Tooltip title={value || "-"}><Typography noWrap sx={{ ...mono, maxWidth: 170 }}>{value ? value.slice(0, 14) : "-"}</Typography></Tooltip>; }
function duration(value: number) { return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`; }
function formatTime(value: string) { return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(value)); }
function statusLabel(value: string) { return ({ RUNNING: "执行中", SUCCEEDED: "成功", FAILED: "失败", CANCELLED: "已取消" } as Record<string, string>)[value] ?? "未知"; }
function domainLabel(value: string) { return ({ REGISTRATION: "注册", LIFECYCLE: "生命周期", INFERENCE: "推理" } as Record<string, string>)[value] ?? "其他"; }
function operationLabel(value: string) { return ({ register: "注册", keepalive: "保活", reauthenticate: "重新认证", probe: "测活" } as Record<string, string>)[value] ?? value; }
