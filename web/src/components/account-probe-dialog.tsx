"use client";

import {
  CheckCircleOutlined,
  CloseOutlined,
  ErrorOutlineOutlined,
  MonitorHeartOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api, type Account } from "@/lib/api";

export function AccountProbeDialog({ account, providerName, onClose }: { account: Account; providerName: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [modelId, setModelId] = useState("");
  const models = useQuery({ queryKey: ["models"], queryFn: api.models });
  const choices = useMemo(() => (models.data?.data ?? [])
    .filter((model) => model.owned_by === account.providerId)
    .map((model) => ({
      id: upstreamModelId(model.id, account.providerId),
      label: upstreamModelId(model.id, account.providerId),
      available: model.available,
      status: model.runtime.status,
    })), [account.providerId, models.data?.data]);

  const selectedModelId = modelId
    || choices.find((model) => model.available)?.id
    || choices[0]?.id
    || "";

  const probe = useMutation({
    mutationFn: () => api.probeAccount(account.id, selectedModelId),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["accounts-page"] }),
        queryClient.invalidateQueries({ queryKey: ["account-detail", account.id] }),
        queryClient.invalidateQueries({ queryKey: ["account-events", account.id] }),
        queryClient.invalidateQueries({ queryKey: ["models"] }),
      ]);
    },
  });

  return (
    <Dialog open onClose={probe.isPending ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
        <Box sx={{ width: 34, height: 34, borderRadius: 1, display: "grid", placeItems: "center", bgcolor: "primary.light", color: "primary.dark" }}>
          <MonitorHeartOutlined sx={{ fontSize: 20 }} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 740 }}>账号推理探测</Typography>
          <Typography noWrap color="text.secondary" sx={{ fontSize: 11.5 }}>{providerName} · {account.email || account.externalId}</Typography>
        </Box>
        <Box sx={{ flex: 1 }} />
        <IconButton aria-label="关闭探测" onClick={onClose} disabled={probe.isPending}><CloseOutlined sx={{ fontSize: 19 }} /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          {models.error ? <Alert severity="error">{models.error.message}</Alert> : null}
          {probe.error ? <Alert severity="error">{probe.error.message}</Alert> : null}
          <TextField
            select
            label="探测模型"
            value={selectedModelId}
            disabled={models.isLoading || probe.isPending}
            onChange={(event) => { setModelId(event.target.value); probe.reset(); }}
          >
            {choices.map((model) => (
              <MenuItem key={model.id} value={model.id}>
                <Stack direction="row" spacing={1} sx={{ width: "100%", alignItems: "center" }}>
                  <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5 }}>{model.label}</Typography>
                  <Box sx={{ flex: 1 }} />
                  <Typography color={model.available ? "success.main" : "text.secondary"} sx={{ fontSize: 10.5 }}>{model.status}</Typography>
                </Stack>
              </MenuItem>
            ))}
          </TextField>
          {models.isSuccess && choices.length === 0 ? <Alert severity="warning">该厂商没有已启用模型</Alert> : null}

          {probe.isPending ? (
            <Box sx={{ minHeight: 180, display: "grid", placeItems: "center", border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "#f8fafb" }}>
              <Stack spacing={1.25} sx={{ alignItems: "center" }}>
                <CircularProgress size={28} />
                <Typography sx={{ fontSize: 12.5, fontWeight: 680 }}>正在等待上游模型响应</Typography>
              </Stack>
            </Box>
          ) : probe.data ? (
            <Box sx={{ border: 1, borderColor: probe.data.ready ? "success.main" : "warning.main", borderRadius: 1, overflow: "hidden" }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", px: 1.5, py: 1.25, bgcolor: probe.data.ready ? "success.light" : "warning.light" }}>
                {probe.data.ready ? <CheckCircleOutlined color="success" sx={{ fontSize: 20 }} /> : <ErrorOutlineOutlined color="warning" sx={{ fontSize: 20 }} />}
                <Box>
                  <Typography sx={{ fontSize: 12.5, fontWeight: 720 }}>{probe.data.ready ? "探测通过" : "探测失败"}</Typography>
                  <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{probe.data.model} · {probe.data.durationMs.toLocaleString("zh-CN")} ms{probe.data.errorClass ? ` · ${probe.data.errorClass}` : ""}</Typography>
                </Box>
              </Stack>
              <Box component="pre" sx={{ m: 0, p: 1.5, minHeight: 112, maxHeight: 260, overflow: "auto", whiteSpace: "pre-wrap", overflowWrap: "anywhere", bgcolor: "#10171b", color: "#dbe5e7", fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace", fontSize: 12, lineHeight: 1.6 }}>
                {probe.data.output || probe.data.errorClass || "上游未返回文本"}
              </Box>
            </Box>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={probe.isPending}>关闭</Button>
        <Button
          variant="contained"
          startIcon={probe.isPending ? <CircularProgress size={15} color="inherit" /> : <MonitorHeartOutlined />}
          disabled={!selectedModelId || probe.isPending}
          onClick={() => probe.mutate()}
        >
          {probe.isPending ? "探测中" : probe.data ? "重新探测" : "发起探测"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function upstreamModelId(id: string, providerId: string) {
  const prefix = `${providerId}/`;
  return id.startsWith(prefix) ? id.slice(prefix.length) : id;
}
