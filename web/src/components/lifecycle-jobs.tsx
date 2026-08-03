"use client";

import {
  AddOutlined,
  CancelOutlined,
  ManageSearchOutlined,
  RefreshOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  IconButton,
  Switch,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  api,
  providerOptions,
  type CaptchaAiMode,
  type ProviderOption,
  type RegistrationJob,
} from "@/lib/api";
import { OperationEventsDialog } from "@/components/operation-events-dialog";
import {
  DataSurface,
  PageContainer,
  PageHeader,
  ToolbarSurface,
} from "@/components/page-layout";

const activeStatuses = new Set(["PENDING", "RUNNING"]);

export function LifecycleJobs() {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [traceJob, setTraceJob] = useState<RegistrationJob | null>(null);
  const catalog = useQuery({
    queryKey: ["providers"],
    queryFn: api.providers,
    refetchInterval: (query) => query.state.data?.automationCatalogReady ? false : 5_000,
  });
  const providers = providerOptions(catalog.data, {
    capability: "REGISTRATION",
    lifecycleOperation: "register",
  });
  const jobs = useQuery({
    queryKey: ["registration-jobs", provider],
    queryFn: () => api.registrationJobs(provider || undefined),
    refetchInterval: (query) => query.state.data?.some((job) => activeStatuses.has(job.status)) ? 3_000 : false,
  });
  const cancel = useMutation({
    mutationFn: api.cancelRegistrationJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["registration-jobs"] }),
  });

  return (
    <PageContainer>
      <PageHeader
        title="生命周期"
        description="批量注册、重新认证与分散保活任务"
        actions={
          <>
            <Tooltip title="刷新任务">
              <IconButton
                aria-label="刷新任务"
                onClick={() => jobs.refetch()}
                sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
              >
                <RefreshOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </Tooltip>
            <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setCreateOpen(true)}>
              新建注册任务
            </Button>
          </>
        }
      />

      <ToolbarSurface>
        <TextField select size="small" label="厂商" value={provider} onChange={(event) => setProvider(event.target.value)} sx={{ width: 260 }}>
          <MenuItem value="">全部厂商</MenuItem>
          {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
        </TextField>
      </ToolbarSurface>
      {catalog.error && <Alert severity="error" sx={{ mb: 2 }}>自动化厂商目录加载失败：{catalog.error.message}</Alert>}
      {catalog.data && !catalog.data.automationCatalogReady && (
        <Alert severity="warning" sx={{ mb: 2 }}>自动化服务尚未就绪，注册入口将在连接后自动加载。</Alert>
      )}
      {jobs.error && <Alert severity="error" sx={{ mb: 2 }}>{jobs.error.message}</Alert>}
      <DataSurface>
        {jobs.isFetching && <LinearProgress />}
        <TableContainer>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>厂商</TableCell><TableCell>状态</TableCell><TableCell>进度</TableCell>
              <TableCell>尝试</TableCell><TableCell>并发</TableCell><TableCell>AI 打码</TableCell><TableCell>节流</TableCell><TableCell>最近错误</TableCell>
              <TableCell>创建时间</TableCell><TableCell align="right">操作</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {(jobs.data ?? []).map((job) => (
                <TableRow key={job.id} hover>
                  <TableCell><Chip size="small" variant="outlined" label={job.providerId} /></TableCell>
                  <TableCell><JobStatus status={job.status} /></TableCell>
                  <TableCell>
                    <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12 }}>
                      {job.successCount} / {job.target} 成功 · {job.failureCount} 失败
                    </Typography>
                  </TableCell>
                  <TableCell>{job.attempts} / {job.maxAttempts}</TableCell>
                  <TableCell>{job.concurrency}</TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      variant="outlined"
                      color={job.aiCaptchaEnabled ? "primary" : "default"}
                      label={job.aiCaptchaEnabled ? captchaModeLabel(job.aiCaptchaMode) : "关闭"}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>
                      启 {job.attemptIntervalSeconds ?? 0}s · 轮 {job.roundIntervalSeconds ?? 5}s
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Tooltip title={job.lastErrorDetail || job.lastErrorClass || ""}>
                      <Box sx={{ minWidth: 0 }}>
                        <Typography noWrap sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5, color: job.lastErrorCode ? "error.main" : "text.secondary" }}>
                          {job.lastErrorCode || job.lastErrorClass || "-"}
                        </Typography>
                        {job.lastErrorStage ? <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>{job.lastErrorStage}</Typography> : null}
                      </Box>
                    </Tooltip>
                  </TableCell>
                  <TableCell>{formatTime(job.createdAt)}</TableCell>
                  <TableCell align="right">
                    <Tooltip title="查看运行轨迹">
                      <IconButton size="small" aria-label="查看运行轨迹" onClick={() => setTraceJob(job)}><ManageSearchOutlined fontSize="small" /></IconButton>
                    </Tooltip>
                    <Tooltip title="取消任务">
                      <span><IconButton size="small" color="error" disabled={!activeStatuses.has(job.status) || cancel.isPending} onClick={() => cancel.mutate(job.id)}><CancelOutlined fontSize="small" /></IconButton></span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!jobs.isLoading && (jobs.data?.length ?? 0) === 0 && <TableRow><TableCell colSpan={10} align="center" sx={{ py: 6, color: "text.secondary" }}>暂无注册任务</TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      </DataSurface>
      {createOpen ? <CreateJobDialog open providers={providers} onClose={() => setCreateOpen(false)} onCreated={() => {
        setCreateOpen(false);
        void queryClient.invalidateQueries({ queryKey: ["registration-jobs"] });
      }} /> : null}
      {traceJob ? (
        <OperationEventsDialog
          open
          title={`${traceJob.providerId} · ${traceJob.id}`}
          queryKey={["registration-job-events", traceJob.id]}
          load={() => api.registrationJobEvents(traceJob.id)}
          onClose={() => setTraceJob(null)}
        />
      ) : null}
    </PageContainer>
  );
}

function CreateJobDialog({ open, providers, onClose, onCreated }: {
  open: boolean; providers: ProviderOption[]; onClose: () => void; onCreated: () => void;
}) {
  const [providerId, setProviderId] = useState("");
  const [target, setTarget] = useState(1);
  const [maxAttempts, setMaxAttempts] = useState(3);
  const [concurrency, setConcurrency] = useState(1);
  const [attemptIntervalSeconds, setAttemptIntervalSeconds] = useState(0);
  const [roundIntervalSeconds, setRoundIntervalSeconds] = useState(5);
  const [aiCaptchaEnabled, setAiCaptchaEnabled] = useState(true);
  const [aiCaptchaMode, setAiCaptchaMode] = useState<CaptchaAiMode>("INTERNAL");
  const mutation = useMutation({
    mutationFn: () => api.createRegistrationJob({
      providerId,
      target,
      maxAttempts,
      concurrency,
      attemptIntervalSeconds,
      roundIntervalSeconds,
      aiCaptchaEnabled,
      aiCaptchaMode,
    }),
    onSuccess: onCreated,
  });
  return <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
    <DialogTitle>新建注册任务</DialogTitle>
    <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
      {mutation.error && <Alert severity="error">{mutation.error.message}</Alert>}
      <TextField select label="厂商" value={providerId} onChange={(event) => setProviderId(event.target.value)}>
        {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
      </TextField>
      <Stack direction="row" spacing={2}>
        <TextField fullWidth label="目标成功数" type="number" value={target} onChange={(event) => setTarget(Number(event.target.value))} slotProps={{ htmlInput: { min: 1, max: 1000 } }} />
        <TextField fullWidth label="最大邮箱任务数" type="number" value={maxAttempts} onChange={(event) => setMaxAttempts(Number(event.target.value))} slotProps={{ htmlInput: { min: target, max: target * 10 } }} />
      </Stack>
      <Stack direction="row" spacing={2}>
        <TextField fullWidth select label="并发数" value={concurrency} onChange={(event) => setConcurrency(Number(event.target.value))}>
          {[1, 2, 3, 4, 6, 8].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}
        </TextField>
        <TextField fullWidth label="任务启动间隔（秒）" type="number" value={attemptIntervalSeconds} onChange={(event) => setAttemptIntervalSeconds(Number(event.target.value))} slotProps={{ htmlInput: { min: 0, max: 3600 } }} />
      </Stack>
      <TextField label="轮次间隔（秒）" type="number" value={roundIntervalSeconds} onChange={(event) => setRoundIntervalSeconds(Number(event.target.value))} slotProps={{ htmlInput: { min: 0, max: 86400 } }} />
      <Stack direction="row" spacing={3} sx={{ alignItems: "center" }}>
        <FormControlLabel
          control={<Switch checked={aiCaptchaEnabled} onChange={(event) => setAiCaptchaEnabled(event.target.checked)} />}
          label="AI 打码"
        />
        <ToggleButtonGroup
          exclusive
          size="small"
          value={aiCaptchaMode}
          disabled={!aiCaptchaEnabled}
          onChange={(_, value: CaptchaAiMode | null) => value && setAiCaptchaMode(value)}
          aria-label="AI 打码来源"
        >
          <ToggleButton value="AUTO">自动</ToggleButton>
          <ToggleButton value="INTERNAL">内置</ToggleButton>
          <ToggleButton value="EXTERNAL">外置</ToggleButton>
        </ToggleButtonGroup>
      </Stack>
    </Stack></DialogContent>
    <DialogActions><Button onClick={onClose}>取消</Button><Button variant="contained" disabled={!providerId || mutation.isPending || maxAttempts < target} onClick={() => mutation.mutate()}>创建</Button></DialogActions>
  </Dialog>;
}

function JobStatus({ status }: { status: RegistrationJob["status"] }) {
  const color = status === "SUCCEEDED" ? "success" : status === "RUNNING" ? "primary" : status === "FAILED" || status === "PARTIAL" ? "error" : "default";
  return <Chip size="small" color={color} variant="outlined" label={status} />;
}

function captchaModeLabel(mode: CaptchaAiMode) {
  return mode === "AUTO" ? "自动" : mode === "EXTERNAL" ? "外置" : "内置";
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
