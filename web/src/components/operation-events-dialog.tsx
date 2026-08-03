"use client";

import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
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
import type { OperationEvent } from "@/lib/api";

export function OperationEventsDialog({
  open,
  title,
  queryKey,
  load,
  onClose,
}: {
  open: boolean;
  title: string;
  queryKey: readonly unknown[];
  load: () => Promise<OperationEvent[]>;
  onClose: () => void;
}) {
  const events = useQuery({
    queryKey,
    queryFn: load,
    enabled: open,
    refetchInterval: (query) => query.state.data?.some((event) => event.status === "RUNNING")
      ? 2_000
      : false,
  });

  return (
    <Dialog open={open} onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>
        <Typography component="span" sx={{ fontSize: 17, fontWeight: 750 }}>运行轨迹</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, fontSize: 12.5 }}>{title}</Typography>
      </DialogTitle>
      <DialogContent dividers sx={{ p: 0, minHeight: 300 }}>
        {events.isFetching ? <LinearProgress sx={{ height: 2 }} /> : null}
        {events.error ? <Alert severity="error" sx={{ m: 2 }}>{events.error.message}</Alert> : null}
        <TableContainer sx={{ maxHeight: 560 }}>
          <Table stickyHeader size="small" sx={{ minWidth: 980 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 60 }}>序号</TableCell>
                <TableCell sx={{ width: 105 }}>状态</TableCell>
                <TableCell sx={{ width: 150 }}>阶段</TableCell>
                <TableCell sx={{ width: 180 }}>错误码</TableCell>
                <TableCell>详情</TableCell>
                <TableCell sx={{ width: 95 }} align="right">耗时</TableCell>
                <TableCell sx={{ width: 170 }}>开始时间</TableCell>
                <TableCell sx={{ width: 130 }}>关联 ID</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(events.data ?? []).map((event) => (
                <TableRow key={event.id} hover>
                  <TableCell sx={{ fontVariantNumeric: "tabular-nums" }}>#{event.attempt}</TableCell>
                  <TableCell><EventStatus status={event.status} /></TableCell>
                  <TableCell>
                    <Typography noWrap title={event.stage} sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>
                      {event.stage}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography noWrap title={event.errorCode ?? ""} sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5, color: event.errorCode ? "error.main" : "text.secondary" }}>
                      {event.errorCode || "-"}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography title={event.errorDetail ?? ""} sx={{ maxWidth: 360, fontSize: 12, lineHeight: 1.45, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {event.errorDetail || "-"}
                    </Typography>
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>
                    {formatDuration(event.durationMs)}
                  </TableCell>
                  <TableCell sx={{ fontSize: 11.5 }}>{formatTime(event.startedAt)}</TableCell>
                  <TableCell>
                    <Tooltip title={event.correlationId} placement="left">
                      <Box component="span" sx={{ display: "block", fontFamily: "ui-monospace, monospace", fontSize: 11, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {event.correlationId.slice(0, 12)}
                      </Box>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!events.isLoading && (events.data?.length ?? 0) === 0 ? (
                <TableRow><TableCell colSpan={8} align="center" sx={{ py: 8, color: "text.secondary" }}>暂无运行事件</TableCell></TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>关闭</Button></DialogActions>
    </Dialog>
  );
}

function EventStatus({ status }: { status: OperationEvent["status"] }) {
  const color = status === "SUCCEEDED" ? "success"
    : status === "FAILED" ? "error"
      : status === "RUNNING" ? "primary"
        : "default";
  return <Chip size="small" variant="outlined" color={color} label={status} />;
}

function formatDuration(value: number) {
  if (value < 1_000) return `${value} ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(1)} s`;
  return `${Math.floor(value / 60_000)}m ${Math.round((value % 60_000) / 1_000)}s`;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}
