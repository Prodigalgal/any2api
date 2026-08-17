"use client";

import {
  AddOutlined,
  DeleteOutlineOutlined,
  EditOutlined,
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
  IconButton,
  LinearProgress,
  MenuItem,
  Stack,
  Switch,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
  useMediaQuery,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  api,
  type ProviderOption,
  type RegistrationJobFormValue,
  type RegistrationSchedule,
  type RegistrationScheduleType,
  type SystemSettings,
} from "@/lib/api";
import { DataSurface, ToolbarSurface } from "@/components/page-layout";
import {
  RegistrationJobFields,
  registrationJobDefaults,
  registrationJobValidation,
} from "@/components/registration-job-form";
import { intervalLabel, scheduleTypeLabel } from "@/components/registration-labels";

const pageSizes = [10, 20, 50];

export function RegistrationSchedules({
  providers,
  settings,
}: {
  providers: ProviderOption[];
  settings?: SystemSettings;
}) {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState("");
  const [enabled, setEnabled] = useState("");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [editing, setEditing] = useState<RegistrationSchedule | "new" | null>(null);
  const providerNames = useMemo(() => new Map(providers), [providers]);
  const schedules = useQuery({
    queryKey: ["registration-schedules", provider, enabled, page, pageSize],
    queryFn: () => api.registrationSchedulePage({
      provider: provider || undefined,
      enabled: enabled === "" ? undefined : enabled === "true",
      page,
      size: pageSize,
    }),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["registration-schedules"] });
  const toggle = useMutation({
    mutationFn: ({ id, nextEnabled }: { id: string; nextEnabled: boolean }) => (
      api.setRegistrationScheduleEnabled(id, nextEnabled)
    ),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: api.deleteRegistrationSchedule,
    onSuccess: async () => {
      if (page > 0 && schedules.data?.items.length === 1) setPage(page - 1);
      await invalidate();
    },
  });

  return (
    <>
      <ToolbarSurface>
        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1.25}
          sx={{ alignItems: { xs: "stretch", sm: "center" } }}
        >
          <TextField
            select
            label="厂商"
            value={provider}
            onChange={(event) => { setProvider(event.target.value); setPage(0); }}
            sx={{ width: { xs: "100%", sm: 220 } }}
          >
            <MenuItem value="">全部厂商</MenuItem>
            {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
          </TextField>
          <TextField
            select
            label="计划状态"
            value={enabled}
            onChange={(event) => { setEnabled(event.target.value); setPage(0); }}
            sx={{ width: { xs: "100%", sm: 180 } }}
          >
            <MenuItem value="">全部状态</MenuItem>
            <MenuItem value="true">仅启用</MenuItem>
            <MenuItem value="false">仅停用</MenuItem>
          </TextField>
          <Box sx={{ flex: 1 }} />
          <Tooltip title="刷新计划">
            <IconButton
              aria-label="刷新计划"
              onClick={() => schedules.refetch()}
              sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
            >
              <RefreshOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setEditing("new")}>
            新建定时计划
          </Button>
        </Stack>
      </ToolbarSurface>

      {schedules.error ? <Alert severity="error" sx={{ mb: 2 }}>{schedules.error.message}</Alert> : null}
      {toggle.error ? <Alert severity="error" sx={{ mb: 2 }}>计划状态更新失败：{toggle.error.message}</Alert> : null}
      {remove.error ? <Alert severity="error" sx={{ mb: 2 }}>计划删除失败：{remove.error.message}</Alert> : null}
      <DataSurface>
        <LinearProgress
          sx={{
            position: "absolute",
            inset: "0 0 auto",
            zIndex: 3,
            visibility: schedules.isFetching ? "visible" : "hidden",
          }}
        />
        <Box sx={{ px: 1.75, height: 44, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
          <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>定时注册计划</Typography>
          <Typography color="text.secondary" sx={{ ml: 1, fontSize: 12 }}>
            {schedules.data ? `${schedules.data.totalElements.toLocaleString("zh-CN")} 个计划` : "正在读取"}
          </Typography>
        </Box>
        <TableContainer sx={{ minHeight: 360, maxHeight: { xs: 560, xl: 690 } }}>
          <Table stickyHeader size="small" sx={{ tableLayout: "fixed", minWidth: 1040 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 180 }}>计划名称</TableCell>
                <TableCell sx={{ width: 100 }}>厂商</TableCell>
                <TableCell sx={{ width: 120 }}>频率</TableCell>
                <TableCell sx={{ width: 150 }}>下次执行</TableCell>
                <TableCell sx={{ width: 150 }}>最近执行</TableCell>
                <TableCell sx={{ width: 150 }}>最近任务</TableCell>
                <TableCell sx={{ width: 90 }}>状态</TableCell>
                <TableCell
                  align="right"
                  sx={{
                    width: 130,
                    position: "sticky",
                    right: 0,
                    zIndex: 4,
                    bgcolor: "#f7f9fc",
                    boxShadow: "-1px 0 0 #e3e9f2",
                  }}
                >
                  操作
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(schedules.data?.items ?? []).map((schedule) => (
                <TableRow key={schedule.id} hover>
                  <TableCell>
                    <Typography noWrap title={schedule.name} sx={{ fontSize: 12.5, fontWeight: 700 }}>
                      {schedule.name}
                    </Typography>
                    <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>
                      {scheduleTypeLabel(schedule.scheduleType)}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography noWrap sx={{ fontSize: 12.5, fontWeight: 650 }}>
                      {providerNames.get(schedule.providerId) ?? schedule.providerId}
                    </Typography>
                    {providerNames.has(schedule.providerId) ? (
                      <Typography noWrap color="text.secondary" sx={{ fontSize: 10.5 }}>{schedule.providerId}</Typography>
                    ) : null}
                  </TableCell>
                  <TableCell>{intervalLabel(schedule.intervalMinutes)}</TableCell>
                  <TableCell>{formatTime(schedule.nextRunAt)}</TableCell>
                  <TableCell>{formatTime(schedule.lastRunAt)}</TableCell>
                  <TableCell>
                    <Tooltip title={schedule.lastError ?? schedule.lastJobId ?? "尚未执行"}>
                      <Typography noWrap sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5, color: schedule.lastError ? "error.main" : "text.secondary" }}>
                        {schedule.lastError ? "执行异常" : schedule.lastJobId ? shortId(schedule.lastJobId) : "尚未执行"}
                      </Typography>
                    </Tooltip>
                  </TableCell>
                  <TableCell><ScheduleStatus schedule={schedule} /></TableCell>
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
                    <Stack direction="row" spacing={0.25} sx={{ justifyContent: "flex-end", alignItems: "center" }}>
                      <Switch
                        size="small"
                        checked={schedule.enabled}
                        disabled={toggle.isPending || (!schedule.nextRunAt && !schedule.enabled)}
                        onChange={(_, nextEnabled) => toggle.mutate({ id: schedule.id, nextEnabled })}
                        slotProps={{ input: { "aria-label": `${schedule.name} 启用状态` } }}
                      />
                      <Tooltip title="编辑计划">
                        <IconButton size="small" aria-label="编辑计划" onClick={() => setEditing(schedule)}>
                          <EditOutlined sx={{ fontSize: 18 }} />
                        </IconButton>
                      </Tooltip>
                      <Tooltip title="删除计划">
                        <IconButton
                          size="small"
                          color="error"
                          aria-label="删除计划"
                          disabled={remove.isPending}
                          onClick={() => {
                            if (window.confirm(`删除定时计划“${schedule.name}”？`)) remove.mutate(schedule.id);
                          }}
                        >
                          <DeleteOutlineOutlined sx={{ fontSize: 18 }} />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {!schedules.isLoading && (schedules.data?.items.length ?? 0) === 0 ? (
                <TableRow><TableCell colSpan={8} align="center" sx={{ height: 240, color: "text.secondary" }}>暂无定时注册计划</TableCell></TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={schedules.data?.totalElements ?? 0}
          page={page}
          rowsPerPage={pageSize}
          rowsPerPageOptions={pageSizes}
          labelRowsPerPage="每页"
          labelDisplayedRows={({ from, to, count }) => `${from}-${to} / 共 ${count} 条`}
          onPageChange={(_, nextPage) => setPage(nextPage)}
          onRowsPerPageChange={(event) => { setPageSize(Number(event.target.value)); setPage(0); }}
        />
      </DataSurface>

      {editing ? (
        <ScheduleDialog
          schedule={editing === "new" ? undefined : editing}
          providers={providers}
          settings={settings}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await invalidate();
          }}
        />
      ) : null}
    </>
  );
}

function ScheduleDialog({
  schedule,
  providers,
  settings,
  onClose,
  onSaved,
}: {
  schedule?: RegistrationSchedule;
  providers: ProviderOption[];
  settings?: SystemSettings;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down("sm"));
  const [name, setName] = useState(schedule?.name ?? "");
  const [scheduleType, setScheduleType] = useState<RegistrationScheduleType>(schedule?.scheduleType ?? "ONCE");
  const [intervalMinutes, setIntervalMinutes] = useState(schedule?.intervalMinutes ?? 60);
  const [firstRunAt, setFirstRunAt] = useState(toLocalDateTime(schedule?.nextRunAt ?? defaultFirstRun()));
  const [job, setJob] = useState<RegistrationJobFormValue>(() => registrationJobDefaults(settings, schedule?.job));
  const validation = registrationJobValidation(job)
    ?? (!name.trim() ? "请输入计划名称" : null)
    ?? (!firstRunAt ? "请选择首次执行时间" : null)
    ?? (scheduleType === "INTERVAL" && (intervalMinutes < 5 || intervalMinutes > 10080)
      ? "循环间隔应为 5 至 10080 分钟" : null);
  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        scheduleType,
        intervalMinutes: scheduleType === "INTERVAL" ? intervalMinutes : null,
        enabled: schedule?.enabled ?? true,
        firstRunAt: new Date(firstRunAt).toISOString(),
        job: { ...job, idempotencyKey: null },
      };
      return schedule
        ? api.updateRegistrationSchedule(schedule.id, body)
        : api.createRegistrationSchedule(body);
    },
    onSuccess: onSaved,
  });

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen}>
      <DialogTitle>{schedule ? "编辑定时注册计划" : "新建定时注册计划"}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5} sx={{ pt: 0.5 }}>
          {save.error ? <Alert severity="error">{save.error.message}</Alert> : null}
          <ToggleButtonGroup
            exclusive
            fullWidth
            value={scheduleType}
            onChange={(_, value: RegistrationScheduleType | null) => value && setScheduleType(value)}
            aria-label="计划执行方式"
          >
            <ToggleButton value="ONCE">仅执行一次</ToggleButton>
            <ToggleButton value="INTERVAL">循环执行</ToggleButton>
          </ToggleButtonGroup>
          <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" }, gap: 2 }}>
            <TextField label="计划名称" value={name} onChange={(event) => setName(event.target.value)} slotProps={{ htmlInput: { maxLength: 120 } }} />
            <TextField
              label="首次执行时间"
              type="datetime-local"
              value={firstRunAt}
              onChange={(event) => setFirstRunAt(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            {scheduleType === "INTERVAL" ? (
              <TextField
                label="循环间隔（分钟）"
                type="number"
                value={intervalMinutes}
                onChange={(event) => setIntervalMinutes(Number(event.target.value))}
                slotProps={{ htmlInput: { min: 5, max: 10080 } }}
              />
            ) : null}
          </Box>
          <RegistrationJobFields value={job} providers={providers} settings={settings} onChange={setJob} />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ position: fullScreen ? "sticky" : "static", bottom: 0, bgcolor: "background.paper", zIndex: 2 }}>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" disabled={Boolean(validation) || save.isPending} onClick={() => save.mutate()}>
          {schedule ? "保存计划" : "创建计划"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ScheduleStatus({ schedule }: { schedule: RegistrationSchedule }) {
  if (schedule.lastError) return <Chip size="small" color="error" variant="outlined" label="异常" />;
  if (schedule.enabled) return <Chip size="small" color="primary" variant="outlined" label="已启用" />;
  if (!schedule.nextRunAt && schedule.scheduleType === "ONCE") {
    return <Chip size="small" color="success" variant="outlined" label="已完成" />;
  }
  return <Chip size="small" variant="outlined" label="已暂停" />;
}

function defaultFirstRun() {
  return new Date(Date.now() + 5 * 60_000).toISOString();
}

function toLocalDateTime(value: string) {
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function formatTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
    : "-";
}

function shortId(value: string) {
  return `${value.slice(0, 8)}…${value.slice(-4)}`;
}
