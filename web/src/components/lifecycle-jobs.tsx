"use client";

import { AddOutlined, CancelOutlined, ManageSearchOutlined, RefreshOutlined } from "@mui/icons-material";
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  IconButton, LinearProgress, MenuItem, Stack, Tab, Table, TableBody, TableCell,
  TableContainer, TableHead, TablePagination, TableRow, Tabs, TextField, Tooltip,
  Typography, useMediaQuery,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  api, providerOptions, type ProviderOption, type RegistrationJob,
  type RegistrationJobFormValue, type SystemSettings,
} from "@/lib/api";
import { OperationEventsDialog } from "@/components/operation-events-dialog";
import { DataSurface, PageContainer, PageHeader, ToolbarSurface } from "@/components/page-layout";
import {
  RegistrationJobFields, registrationJobDefaults, registrationJobValidation,
} from "@/components/registration-job-form";
import { RegistrationSchedules } from "@/components/registration-schedules";
import {
  captchaModeLabel, registrationJobStatusLabel, registrationProxyPolicyLabel,
} from "@/components/registration-labels";

const activeStatuses = new Set(["PENDING", "RUNNING"]);
const pageSizes = [10, 20, 50];
const statusOptions = [
  ["PENDING", "等待执行"], ["RUNNING", "执行中"], ["SUCCEEDED", "已成功"],
  ["PARTIAL", "部分成功"], ["FAILED", "已失败"], ["CANCELLED", "已取消"],
] as const;

export function LifecycleJobs() {
  const [view, setView] = useState<"jobs" | "schedules">("jobs");
  const catalog = useQuery({
    queryKey: ["providers"],
    queryFn: api.providers,
    refetchInterval: (query) => query.state.data?.automationCatalogReady ? false : 5_000,
  });
  const settings = useQuery({ queryKey: ["system-settings"], queryFn: api.systemSettings });
  const providers = providerOptions(catalog.data, {
    capability: "REGISTRATION", lifecycleOperation: "register",
  });

  return (
    <PageContainer>
      <Box sx={{ display: { xs: "none", sm: "block" } }}>
        <PageHeader title="注册与生命周期" description="管理账号注册任务、定时计划和执行轨迹" />
      </Box>
      <Box sx={{ mb: 2.25, borderBottom: 1, borderColor: "divider" }}>
        <Tabs value={view} onChange={(_, value: "jobs" | "schedules") => setView(value)} aria-label="注册管理视图">
          <Tab value="jobs" label="注册任务" />
          <Tab value="schedules" label="定时注册" />
        </Tabs>
      </Box>
      {catalog.error ? <Alert severity="error" sx={{ mb: 2 }}>自动化厂商目录加载失败：{catalog.error.message}</Alert> : null}
      {catalog.data && !catalog.data.automationCatalogReady ? (
        <Alert severity="warning" sx={{ mb: 2 }}>自动化服务尚未就绪，注册入口将在连接后自动加载。</Alert>
      ) : null}
      {view === "jobs" ? (
        <RegistrationJobsPanel providers={providers} settings={settings.data} settingsLoading={settings.isLoading} />
      ) : <RegistrationSchedules providers={providers} settings={settings.data} />}
    </PageContainer>
  );
}

function RegistrationJobsPanel({ providers, settings, settingsLoading }: {
  providers: ProviderOption[]; settings?: SystemSettings; settingsLoading: boolean;
}) {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [createOpen, setCreateOpen] = useState(false);
  const [traceJob, setTraceJob] = useState<RegistrationJob | null>(null);
  const providerNames = useMemo(() => new Map(providers), [providers]);
  const jobs = useQuery({
    queryKey: ["registration-jobs", provider, status, page, pageSize],
    queryFn: () => api.registrationJobPage({
      provider: provider || undefined, status: status || undefined, page, size: pageSize,
    }),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => query.state.data?.items.some((job) => activeStatuses.has(job.status)) ? 3_000 : false,
  });
  const cancel = useMutation({
    mutationFn: api.cancelRegistrationJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["registration-jobs"] }),
  });

  return (
    <>
      <ToolbarSurface>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1.25} sx={{ alignItems: { xs: "stretch", sm: "center" } }}>
          <TextField select label="厂商" value={provider} onChange={(event) => { setProvider(event.target.value); setPage(0); }} sx={{ width: { xs: "100%", sm: 220 } }}>
            <MenuItem value="">全部厂商</MenuItem>
            {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
          </TextField>
          <TextField select label="任务状态" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }} sx={{ width: { xs: "100%", sm: 180 } }}>
            <MenuItem value="">全部状态</MenuItem>
            {statusOptions.map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}
          </TextField>
          <Box sx={{ flex: 1 }} />
          <Tooltip title="刷新任务">
            <IconButton aria-label="刷新任务" onClick={() => jobs.refetch()} sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}>
              <RefreshOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Button variant="contained" startIcon={<AddOutlined />} disabled={settingsLoading} onClick={() => setCreateOpen(true)}>
            新建注册任务
          </Button>
        </Stack>
      </ToolbarSurface>
      {jobs.error ? <Alert severity="error" sx={{ mb: 2 }}>{jobs.error.message}</Alert> : null}
      <DataSurface>
        <LinearProgress sx={{ position: "absolute", inset: "0 0 auto", zIndex: 3, visibility: jobs.isFetching ? "visible" : "hidden" }} />
        <Box sx={{ px: 1.75, height: 44, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
          <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>历史注册任务</Typography>
          <Typography color="text.secondary" sx={{ ml: 1, fontSize: 12 }}>
            {jobs.data ? `${jobs.data.totalElements.toLocaleString("zh-CN")} 个任务` : "正在读取"}
          </Typography>
        </Box>
        <TableContainer sx={{ minHeight: 380, maxHeight: { xs: 560, xl: 690 } }}>
          <Table stickyHeader size="small" sx={{ tableLayout: "fixed", minWidth: 1210 }}>
            <TableHead><TableRow>
              <TableCell sx={{ width: 95 }}>厂商</TableCell><TableCell sx={{ width: 90 }}>状态</TableCell>
              <TableCell sx={{ width: 145 }}>进度</TableCell><TableCell sx={{ width: 80 }}>尝试次数</TableCell>
              <TableCell sx={{ width: 185 }}>执行策略</TableCell><TableCell sx={{ width: 105 }}>验证码识别</TableCell>
              <TableCell sx={{ width: 120 }}>节流设置</TableCell><TableCell sx={{ width: 150 }}>最近错误</TableCell>
              <TableCell sx={{ width: 140 }}>创建时间</TableCell>
              <TableCell
                align="right"
                sx={{
                  width: 100,
                  position: "sticky",
                  right: 0,
                  zIndex: 4,
                  bgcolor: "#f7f9fc",
                  boxShadow: "-1px 0 0 #e3e9f2",
                }}
              >
                操作
              </TableCell>
            </TableRow></TableHead>
            <TableBody>
              {(jobs.data?.items ?? []).map((job) => (
                <TableRow key={job.id} hover>
                  <TableCell>
                    <Typography noWrap sx={{ fontSize: 12.5, fontWeight: 700 }}>{providerNames.get(job.providerId) ?? job.providerId}</Typography>
                    {providerNames.has(job.providerId) ? <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>{job.providerId}</Typography> : null}
                  </TableCell>
                  <TableCell><JobStatus status={job.status} /></TableCell>
                  <TableCell><Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>成功 {job.successCount} / {job.target} · 失败 {job.failureCount}</Typography></TableCell>
                  <TableCell>{job.attempts} / {job.maxAttempts}</TableCell>
                  <TableCell>
                    <Typography sx={{ fontSize: 11.5 }}>{job.concurrency} 并发 · 每邮箱 {job.flowMaxAttempts} 个流程</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{registrationProxyPolicyLabel(job.proxyPolicy)} · {job.headless ? "无界面浏览器" : "可视浏览器"}</Typography>
                  </TableCell>
                  <TableCell><Chip size="small" variant="outlined" color={job.aiCaptchaEnabled ? "primary" : "default"} label={job.aiCaptchaEnabled ? captchaModeLabel(job.aiCaptchaMode) : "已关闭"} /></TableCell>
                  <TableCell>
                    <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>启动 {job.attemptIntervalSeconds ?? 0} 秒</Typography>
                    <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>轮次 {job.roundIntervalSeconds ?? 5} 秒</Typography>
                  </TableCell>
                  <TableCell>
                    <Tooltip title={job.lastErrorDetail || job.lastErrorClass || "无错误"}>
                      <Box sx={{ minWidth: 0 }}>
                        <Typography noWrap sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5, color: job.lastErrorCode ? "error.main" : "text.secondary" }}>{job.lastErrorCode || job.lastErrorClass || "无"}</Typography>
                        {job.lastErrorStage ? <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>{job.lastErrorStage}</Typography> : null}
                      </Box>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{formatTime(job.createdAt)}</TableCell>
                  <TableCell
                    align="right"
                    sx={{
                      position: "sticky",
                      right: 0,
                      zIndex: 2,
                      bgcolor: "background.paper",
                      boxShadow: "-1px 0 0 #e3e9f2",
                      "tr:hover &": { bgcolor: "#f3f7fe" },
                    }}
                  >
                    <Tooltip title="查看运行轨迹"><IconButton size="small" aria-label="查看运行轨迹" onClick={() => setTraceJob(job)}><ManageSearchOutlined sx={{ fontSize: 18 }} /></IconButton></Tooltip>
                    <Tooltip title="取消任务"><span><IconButton size="small" color="error" aria-label="取消任务" disabled={!activeStatuses.has(job.status) || cancel.isPending} onClick={() => cancel.mutate(job.id)}><CancelOutlined sx={{ fontSize: 18 }} /></IconButton></span></Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!jobs.isLoading && (jobs.data?.items.length ?? 0) === 0 ? <TableRow><TableCell colSpan={10} align="center" sx={{ height: 240, color: "text.secondary" }}>暂无注册任务</TableCell></TableRow> : null}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination component="div" count={jobs.data?.totalElements ?? 0} page={page} rowsPerPage={pageSize} rowsPerPageOptions={pageSizes} labelRowsPerPage="每页" labelDisplayedRows={({ from, to, count }) => `${from}-${to} / 共 ${count} 条`} onPageChange={(_, nextPage) => setPage(nextPage)} onRowsPerPageChange={(event) => { setPageSize(Number(event.target.value)); setPage(0); }} />
      </DataSurface>
      {createOpen ? <CreateJobDialog providers={providers} settings={settings} onClose={() => setCreateOpen(false)} onCreated={async () => { setCreateOpen(false); setPage(0); await queryClient.invalidateQueries({ queryKey: ["registration-jobs"] }); }} /> : null}
      {traceJob ? <OperationEventsDialog open title={`${providerNames.get(traceJob.providerId) ?? traceJob.providerId} · ${traceJob.id}`} queryKey={["registration-job-events", traceJob.id]} load={() => api.registrationJobEvents(traceJob.id)} onClose={() => setTraceJob(null)} /> : null}
    </>
  );
}

function CreateJobDialog({ providers, settings, onClose, onCreated }: {
  providers: ProviderOption[]; settings?: SystemSettings; onClose: () => void; onCreated: () => void | Promise<void>;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down("sm"));
  const [value, setValue] = useState<RegistrationJobFormValue>(() => registrationJobDefaults(settings));
  const validation = registrationJobValidation(value);
  const create = useMutation({ mutationFn: () => api.createRegistrationJob({ ...value, idempotencyKey: null }), onSuccess: onCreated });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen}>
      <DialogTitle>新建注册任务</DialogTitle>
      <DialogContent dividers><Stack spacing={2} sx={{ pt: 0.5 }}>{create.error ? <Alert severity="error">{create.error.message}</Alert> : null}<RegistrationJobFields value={value} providers={providers} settings={settings} onChange={setValue} /></Stack></DialogContent>
      <DialogActions sx={{ position: fullScreen ? "sticky" : "static", bottom: 0, bgcolor: "background.paper" }}><Button onClick={onClose}>取消</Button><Button variant="contained" disabled={Boolean(validation) || create.isPending} onClick={() => create.mutate()}>创建任务</Button></DialogActions>
    </Dialog>
  );
}

function JobStatus({ status }: { status: string }) {
  const color = status === "SUCCEEDED" ? "success" : status === "RUNNING" ? "primary" : status === "FAILED" || status === "PARTIAL" ? "error" : "default";
  return <Chip size="small" color={color} variant="outlined" label={registrationJobStatusLabel(status)} />;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
