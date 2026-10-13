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
import { isInitialVerificationModel } from "@/lib/model-policy";
import { tokens } from "@/theme/theme";

export function AccountProbeDialog({ account, providerName, onClose }: { account: Account; providerName: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [modelId, setModelId] = useState("");
  const detail = useQuery({ queryKey: ["account-detail", account.id], queryFn: () => api.accountDetail(account.id) });
  const verifiedBefore = Boolean(detail.data?.account.metadata.inference_probe_at);
  const providers = useQuery({ queryKey: ["admin-providers"], queryFn: api.adminProviders });
  const configuredModel = providers.data?.find((provider) => provider.id === account.providerId)?.probeModel || "";
  const models = useQuery({ queryKey: ["models"], queryFn: api.models });
  const choices = useMemo(() => (models.data?.data ?? [])
    .filter((model) => model.owned_by === account.providerId && isInitialVerificationModel(model.id))
    .map((model) => ({
      id: upstreamModelId(model.id, account.providerId),
      label: upstreamModelId(model.id, account.providerId),
    })), [account.providerId, models.data?.data]);

  const selectedModelId = modelId;

  const probe = useMutation({
    mutationFn: () => api.probeAccount(account.id, verifiedBefore ? undefined : selectedModelId || undefined),
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
        <Box sx={{ width: 34, height: 34, borderRadius: "8px", display: "grid", placeItems: "center", bgcolor: tokens.primary.light, color: tokens.primary.main, border: "1px solid rgba(59, 130, 246, 0.3)" }}>
          <MonitorHeartOutlined sx={{ fontSize: 20 }} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 740 }}>账号首次验证</Typography>
          <Typography noWrap color="text.secondary" sx={{ fontSize: 11.5 }}>{providerName} · {account.email || account.externalId}</Typography>
        </Box>
        <Box sx={{ flex: 1 }} />
        <IconButton aria-label="关闭验证" onClick={onClose} disabled={probe.isPending}><CloseOutlined sx={{ fontSize: 19 }} /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2} sx={{ pt: 0.5 }}>
          <Alert severity="info">账号入库后只验证一次。已有记录时直接返回历史结果，不重复生成，也不改变当前账号状态。</Alert>
          {models.error ? <Alert severity="error">{models.error.message}</Alert> : null}
          {probe.error ? <Alert severity="error">{probe.error.message}</Alert> : null}
          <TextField
            select
            label="首次验证模型"
            helperText={configuredModel ? `厂商默认：${upstreamModelId(configuredModel, account.providerId)}` : "未选择时使用厂商配置的基础模型"}
            value={selectedModelId}
            disabled={verifiedBefore || models.isLoading || probe.isPending}
            onChange={(event) => { setModelId(event.target.value); probe.reset(); }}
          >
            <MenuItem value="">厂商配置的基础模型（默认）</MenuItem>
            {choices.map((model) => (
              <MenuItem key={model.id} value={model.id}>
                <Stack direction="row" spacing={1} sx={{ width: "100%", alignItems: "center" }}>
                  <Typography sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12.5 }}>{model.label}</Typography>
                </Stack>
              </MenuItem>
            ))}
          </TextField>
          {models.isSuccess && choices.length === 0 ? <Alert severity="warning">该厂商没有已编目的轻量模型，将使用后端默认配置</Alert> : null}

          {probe.isPending ? (
            <Box sx={{ minHeight: 180, display: "grid", placeItems: "center", border: 1, borderColor: "divider", borderRadius: 1, bgcolor: tokens.canvasSubtle }}>
              <Stack spacing={1.25} sx={{ alignItems: "center" }}>
                <CircularProgress size={28} />
                <Typography sx={{ fontSize: 12.5, fontWeight: 680 }}>正在读取记录或等待首次验证结果</Typography>
              </Stack>
            </Box>
          ) : probe.data ? (
            <Stack spacing={1}>
              {!probe.data.performed ? <Alert severity="info">已有验证记录，本次没有向厂商发送生成请求。</Alert> : null}
            <Box sx={{ border: 1, borderColor: probe.data.ready ? "success.main" : "warning.main", borderRadius: 1, overflow: "hidden" }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center", px: 1.5, py: 1.25, bgcolor: probe.data.ready ? "success.light" : "warning.light" }}>
                {probe.data.ready ? <CheckCircleOutlined color="success" sx={{ fontSize: 20 }} /> : <ErrorOutlineOutlined color="warning" sx={{ fontSize: 20 }} />}
                <Box>
                  <Typography sx={{ fontSize: 12.5, fontWeight: 720 }}>{probe.data.ready ? "首次验证通过" : "首次验证失败"}</Typography>
                  <Typography color="text.secondary" sx={{ fontSize: 10.5 }}>{probe.data.model} · {probe.data.durationMs.toLocaleString("zh-CN")} ms{probe.data.errorClass ? ` · ${probe.data.errorClass}` : ""}</Typography>
                </Box>
              </Stack>
              <Box component="pre" sx={{ m: 0, p: 1.5, minHeight: 112, maxHeight: 260, overflow: "auto", whiteSpace: "pre-wrap", overflowWrap: "anywhere", bgcolor: "#10171b", color: "#dbe5e7", fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace", fontSize: 12, lineHeight: 1.6 }}>
                {probe.data.output || probe.data.errorClass || "上游未返回文本"}
              </Box>
            </Box>
            </Stack>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={probe.isPending}>关闭</Button>
        <Button
          variant="contained"
          startIcon={probe.isPending ? <CircularProgress size={15} color="inherit" /> : <MonitorHeartOutlined />}
          disabled={probe.isPending}
          onClick={() => probe.mutate()}
        >
          {probe.isPending ? "读取中" : probe.data || verifiedBefore ? "查看验证记录" : "执行首次验证"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function upstreamModelId(id: string, providerId: string) {
  const prefix = `${providerId}/`;
  return id.startsWith(prefix) ? id.slice(prefix.length) : id;
}
