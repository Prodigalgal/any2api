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
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
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
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  api,
  providerOptions,
  type ProviderOption,
  type ProxyPool,
  type ProxyTrafficScope,
} from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

const trafficScopes: Array<[ProxyTrafficScope, string]> = [
  ["REGISTRATION", "注册"],
  ["LIFECYCLE", "保活/重授权"],
  ["INFERENCE", "公共推理"],
];

export function ProxyPools() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<ProxyPool | null | undefined>(undefined);
  const pools = useQuery({ queryKey: ["proxy-pools"], queryFn: api.proxyPools });
  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providers = providerOptions(catalog.data);
  const remove = useMutation({
    mutationFn: api.deleteProxyPool,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["proxy-pools"] }),
  });

  return <PageContainer>
    <PageHeader
      title="代理池"
      description="管理自动化出口、流量边界与厂商绑定"
      actions={
        <>
          <Tooltip title="刷新代理池">
            <IconButton
              aria-label="刷新代理池"
              onClick={() => pools.refetch()}
              sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
            >
              <RefreshOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setEditing(null)}>
            新建代理池
          </Button>
        </>
      }
    />

    {pools.error && <Alert severity="error" sx={{ mb: 2 }}>{pools.error.message}</Alert>}
    {remove.error && <Alert severity="error" sx={{ mb: 2 }}>{remove.error.message}</Alert>}
    <DataSurface>
      {pools.isFetching && <LinearProgress />}
      <TableContainer>
        <Table size="small">
          <TableHead><TableRow>
            <TableCell>名称</TableCell><TableCell>来源</TableCell><TableCell>节点</TableCell>
            <TableCell>绑定厂商</TableCell><TableCell>状态</TableCell><TableCell>更新时间</TableCell>
            <TableCell align="right">操作</TableCell>
          </TableRow></TableHead>
          <TableBody>
            {(pools.data ?? []).map((pool) => <TableRow key={pool.id} hover>
              <TableCell sx={{ fontWeight: 700 }}>{pool.name}</TableCell>
              <TableCell>{pool.mode === "SUBSCRIPTION_URL" ? "订阅 URL" : "节点列表"}</TableCell>
              <TableCell>{pool.mode === "NODE_LIST" ? pool.nodeCount : "动态"}</TableCell>
              <TableCell>
                <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                  {Object.entries(pool.bindingScopes).map(([id, scopes]) => <Chip
                    key={id}
                    size="small"
                    variant="outlined"
                    label={`${id} · ${scopes.map(scopeLabel).join("/")}`}
                  />)}
                  {!pool.providerIds.length && <Typography color="text.secondary" sx={{ fontSize: 12 }}>未绑定</Typography>}
                </Stack>
              </TableCell>
              <TableCell><Chip size="small" color={pool.enabled ? "success" : "default"} variant="outlined" label={pool.enabled ? "启用" : "停用"} /></TableCell>
              <TableCell>{formatTime(pool.updatedAt)}</TableCell>
              <TableCell align="right">
                <Tooltip title="编辑"><IconButton size="small" onClick={() => setEditing(pool)}><EditOutlined fontSize="small" /></IconButton></Tooltip>
                <Tooltip title="删除"><IconButton size="small" color="error" disabled={remove.isPending} onClick={() => {
                  if (window.confirm(`删除代理池 ${pool.name}？`)) remove.mutate(pool.id);
                }}><DeleteOutlineOutlined fontSize="small" /></IconButton></Tooltip>
              </TableCell>
            </TableRow>)}
            {!pools.isLoading && !pools.data?.length && <TableRow><TableCell colSpan={7} align="center" sx={{ py: 6, color: "text.secondary" }}>暂无代理池</TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
    </DataSurface>
    {editing !== undefined && <PoolDialog
      pool={editing}
      providers={providers}
      onClose={() => setEditing(undefined)}
      onSaved={() => {
        setEditing(undefined);
        void queryClient.invalidateQueries({ queryKey: ["proxy-pools"] });
      }}
    />}
  </PageContainer>;
}

function PoolDialog({ pool, providers, onClose, onSaved }: {
  pool: ProxyPool | null;
  providers: ProviderOption[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(pool?.name ?? "");
  const [mode, setMode] = useState<ProxyPool["mode"]>(pool?.mode ?? "SUBSCRIPTION_URL");
  const [source, setSource] = useState("");
  const [enabled, setEnabled] = useState(pool?.enabled ?? true);
  const [bindingScopes, setBindingScopes] = useState<Record<string, ProxyTrafficScope[]>>(
    pool?.bindingScopes ?? Object.fromEntries(
      (pool?.providerIds ?? []).map((id) => [id, ["REGISTRATION"]]),
    ),
  );
  const save = useMutation({
    mutationFn: () => {
      const normalizedBindings = Object.fromEntries(
        Object.entries(bindingScopes).filter(([, scopes]) => scopes.length > 0),
      );
      const body = {
        name,
        mode,
        source,
        enabled,
        providerIds: Object.keys(normalizedBindings),
        bindingScopes: normalizedBindings,
      };
      return pool ? api.updateProxyPool(pool.id, body) : api.createProxyPool(body);
    },
    onSuccess: onSaved,
  });
  const sourceRequired = !pool || mode !== pool.mode;

  return <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
    <DialogTitle>{pool ? "编辑代理池" : "新建代理池"}</DialogTitle>
    <DialogContent><Stack spacing={2} sx={{ pt: 1 }}>
      {save.error && <Alert severity="error">{save.error.message}</Alert>}
      <TextField label="名称" value={name} onChange={(event) => setName(event.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
      <TextField select label="来源类型" value={mode} onChange={(event) => setMode(event.target.value as ProxyPool["mode"])}>
        <MenuItem value="SUBSCRIPTION_URL">订阅 URL</MenuItem>
        <MenuItem value="NODE_LIST">节点列表</MenuItem>
      </TextField>
      <TextField
        label={mode === "SUBSCRIPTION_URL" ? "订阅 URL" : "代理节点"}
        value={source}
        onChange={(event) => setSource(event.target.value)}
        multiline={mode === "NODE_LIST"}
        minRows={mode === "NODE_LIST" ? 7 : undefined}
        placeholder={pool ? "留空则保留当前加密配置" : mode === "NODE_LIST" ? "每行一个 VLESS、HTTP 或 SOCKS 节点" : "https://..."}
        helperText={pool ? "已保存的源不会返回浏览器；填写内容会整体替换。" : undefined}
      />
      <Box>
        <Typography sx={{ mb: 1, fontSize: 13, fontWeight: 700 }}>厂商流量绑定</Typography>
        <Box sx={{
          display: "grid",
          gridTemplateColumns: "minmax(150px, 1fr) repeat(3, minmax(92px, auto))",
          border: "1px solid",
          borderColor: "divider",
          borderRadius: 1,
          overflow: "hidden",
          "& > *": { px: 1.25, py: 0.8, borderBottom: "1px solid", borderColor: "divider" },
        }}>
          <Typography color="text.secondary" sx={{ fontSize: 11, fontWeight: 700 }}>厂商</Typography>
          {trafficScopes.map(([scope, label]) => <Typography
            key={scope}
            color="text.secondary"
            align="center"
            sx={{ fontSize: 11, fontWeight: 700 }}
          >{label}</Typography>)}
          {providers.flatMap(([id, label], providerIndex) => {
            const selected = bindingScopes[id] ?? [];
            const last = providerIndex === providers.length - 1;
            return [
              <Box key={`${id}-label`} sx={{ borderBottom: last ? "0 !important" : undefined }}>
                <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{label}</Typography>
                <Typography color="text.secondary" sx={{ fontSize: 11 }}>{id}</Typography>
              </Box>,
              ...trafficScopes.map(([scope, scopeName]) => <Box
                key={`${id}-${scope}`}
                sx={{ textAlign: "center", borderBottom: last ? "0 !important" : undefined }}
              >
                <Checkbox
                  size="small"
                  slotProps={{ input: { "aria-label": `${label} ${scopeName}` } }}
                  checked={selected.includes(scope)}
                  onChange={(_, checked) => setBindingScopes((current) => ({
                    ...current,
                    [id]: checked
                      ? [...new Set([...(current[id] ?? []), scope])]
                      : (current[id] ?? []).filter((value) => value !== scope),
                  }))}
                />
              </Box>),
            ];
          })}
        </Box>
        <Typography color="text.secondary" sx={{ mt: 0.75, fontSize: 11 }}>
          默认仅注册使用代理；未勾选的流量保持直连。
        </Typography>
      </Box>
      <FormControlLabel control={<Switch checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />} label="启用代理池" />
    </Stack></DialogContent>
    <DialogActions>
      <Button onClick={onClose}>取消</Button>
      <Button variant="contained" disabled={save.isPending || !name.trim() || (sourceRequired && !source.trim())} onClick={() => save.mutate()}>保存</Button>
    </DialogActions>
  </Dialog>;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function scopeLabel(scope: ProxyTrafficScope) {
  return trafficScopes.find(([value]) => value === scope)?.[1] ?? scope;
}
