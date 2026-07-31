"use client";

import {
  AddOutlined,
  DeleteOutlineOutlined,
  RefreshOutlined,
  LoginOutlined,
  MoreVertOutlined,
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
  MenuItem,
  Menu,
  Paper,
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
import { useState, type MouseEvent } from "react";
import { api, providerOptions, type Account, type ProviderOption } from "@/lib/api";

export function Accounts() {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState("");
  const [importOpen, setImportOpen] = useState(false);
  const [commandAccount, setCommandAccount] = useState<Account | null>(null);
  const [commandAnchor, setCommandAnchor] = useState<HTMLElement | null>(null);

  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providers = providerOptions(catalog.data);
  const accounts = useQuery({
    queryKey: ["accounts", provider],
    queryFn: () => api.accounts(provider || undefined),
    retry: false,
  });
  const update = useMutation({
    mutationFn: ({ account, enabled }: { account: Account; enabled: boolean }) =>
      api.updateAccount(account.id, { enabled, status: enabled ? "ACTIVE" : "DISABLED" }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["accounts"] }),
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteAccount(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["accounts"] }),
  });
  const reauthenticate = useMutation({
    mutationFn: (id: string) => api.reauthenticateAccount(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["accounts"] }),
  });
  const commands = useQuery({
    queryKey: ["account-commands", commandAccount?.id],
    queryFn: () => api.accountCommands(commandAccount!.id),
    enabled: Boolean(commandAccount),
    retry: false,
  });
  const executeCommand = useMutation({
    mutationFn: ({ accountId, command }: { accountId: string; command: string }) =>
      api.executeAccountCommand(accountId, command),
    onSuccess: () => {
      setCommandAnchor(null);
      setCommandAccount(null);
      void queryClient.invalidateQueries({ queryKey: ["accounts"] });
    },
  });

  const openCommands = (event: MouseEvent<HTMLElement>, account: Account) => {
    setCommandAnchor(event.currentTarget);
    setCommandAccount(account);
  };

  return (
    <Box sx={{ px: 3.5, py: 3, width: "100%", minWidth: 980 }}>
      <Stack direction="row" sx={{ mb: 2.5, alignItems: "flex-start", justifyContent: "space-between" }}>
        <Box>
          <Typography variant="h4">账号池</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5, fontSize: 13 }}>
            厂商隔离账号、凭据版本与运行健康状态
          </Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          <Button variant="outlined" startIcon={<RefreshOutlined />} onClick={() => accounts.refetch()}>
            刷新
          </Button>
          <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setImportOpen(true)}>
            导入账号
          </Button>
        </Stack>
      </Stack>

      <Paper variant="outlined" sx={{ mb: 2, p: 1.5 }}>
        <TextField
          select
          size="small"
          label="厂商"
          value={provider}
          onChange={(event) => setProvider(event.target.value)}
          sx={{ width: 260 }}
        >
          <MenuItem value="">全部厂商</MenuItem>
          {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
        </TextField>
      </Paper>

      {(accounts.error || commands.error || executeCommand.error) && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {(accounts.error || commands.error || executeCommand.error)?.message}
        </Alert>
      )}
      <Paper variant="outlined" sx={{ overflow: "hidden" }}>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>厂商</TableCell>
                <TableCell>上游账号</TableCell>
                <TableCell>邮箱</TableCell>
                <TableCell>状态</TableCell>
                <TableCell align="right">请求 / 成功 / 失败</TableCell>
                <TableCell>到期时间</TableCell>
                <TableCell align="center">启用</TableCell>
                <TableCell align="right">操作</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(accounts.data ?? []).map((account) => (
                <TableRow key={account.id} hover>
                  <TableCell><Chip size="small" variant="outlined" label={account.providerId} /></TableCell>
                  <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12 }}>{account.externalId}</TableCell>
                  <TableCell>{account.email || "-"}</TableCell>
                  <TableCell><StatusChip status={account.status} /></TableCell>
                  <TableCell align="right" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 12 }}>
                    {account.requestCount} / {account.successCount} / {account.failureCount}
                  </TableCell>
                  <TableCell>{formatTime(account.expiresAt)}</TableCell>
                  <TableCell align="center">
                    <Switch
                      size="small"
                      checked={account.enabled}
                      disabled={update.isPending}
                      onChange={(_, enabled) => update.mutate({ account, enabled })}
                      slotProps={{ input: { "aria-label": `${account.externalId} 启用状态` } }}
                    />
                  </TableCell>
                  <TableCell align="right">
                    <Tooltip title="厂商账号操作">
                      <IconButton
                        size="small"
                        disabled={!account.enabled}
                        onClick={(event) => openCommands(event, account)}
                      ><MoreVertOutlined fontSize="small" /></IconButton>
                    </Tooltip>
                    <Tooltip title="重新认证">
                      <IconButton
                        size="small"
                        disabled={reauthenticate.isPending || !account.enabled}
                        onClick={() => reauthenticate.mutate(account.id)}
                      ><LoginOutlined fontSize="small" /></IconButton>
                    </Tooltip>
                    <Tooltip title="删除账号">
                      <IconButton
                        size="small"
                        color="error"
                        disabled={remove.isPending}
                        onClick={() => {
                          if (window.confirm(`删除 ${account.providerId}/${account.externalId}？`)) remove.mutate(account.id);
                        }}
                      ><DeleteOutlineOutlined fontSize="small" /></IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!accounts.isLoading && (accounts.data?.length ?? 0) === 0 && (
                <TableRow><TableCell colSpan={8} align="center" sx={{ py: 6, color: "text.secondary" }}>暂无账号</TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <Menu
        anchorEl={commandAnchor}
        open={Boolean(commandAnchor)}
        onClose={() => {
          setCommandAnchor(null);
          setCommandAccount(null);
        }}
      >
        {commands.isLoading && <MenuItem disabled>正在加载...</MenuItem>}
        {!commands.isLoading && (commands.data?.length ?? 0) === 0 && (
          <MenuItem disabled>该厂商没有账号操作</MenuItem>
        )}
        {(commands.data ?? []).map((command) => (
          <MenuItem
            key={command.name}
            disabled={executeCommand.isPending}
            onClick={() => {
              if (!commandAccount) return;
              executeCommand.mutate({ accountId: commandAccount.id, command: command.name });
            }}
          >
            {command.displayName}
          </MenuItem>
        ))}
      </Menu>

      <ImportDialog
        open={importOpen}
        providers={providers}
        onClose={() => setImportOpen(false)}
        onImported={() => {
          setImportOpen(false);
          void queryClient.invalidateQueries({ queryKey: ["accounts"] });
        }}
      />
    </Box>
  );
}

function ImportDialog({
  open,
  providers,
  onClose,
  onImported,
}: {
  open: boolean;
  providers: ProviderOption[];
  onClose: () => void;
  onImported: () => void;
}) {
  const [providerId, setProviderId] = useState("");
  const [externalId, setExternalId] = useState("");
  const [email, setEmail] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [credential, setCredential] = useState("{\n  \"token\": \"\"\n}");
  const mutation = useMutation({
    mutationFn: () => {
      let parsed: unknown;
      try { parsed = JSON.parse(credential); } catch { throw new Error("凭据不是有效 JSON"); }
      return api.importAccount({
        providerId,
        externalId,
        email: email || null,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
        credential: parsed,
      });
    },
    onSuccess: onImported,
  });
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>导入或轮换账号</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {mutation.error && <Alert severity="error">{mutation.error.message}</Alert>}
          <TextField select label="厂商" value={providerId} onChange={(event) => setProviderId(event.target.value)}>
            {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
          </TextField>
          <TextField label="上游账号 ID" value={externalId} onChange={(event) => setExternalId(event.target.value)} />
          <TextField label="邮箱" value={email} onChange={(event) => setEmail(event.target.value)} />
          <TextField label="账号到期时间" type="datetime-local" value={expiresAt} onChange={(event) => setExpiresAt(event.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField label="凭据 JSON" value={credential} onChange={(event) => setCredential(event.target.value)} multiline minRows={8} slotProps={{ htmlInput: { spellCheck: false } }} sx={{ "& textarea": { fontFamily: "ui-monospace, monospace", fontSize: 12 } }} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" onClick={() => mutation.mutate()} disabled={!providerId || !externalId || mutation.isPending}>保存</Button>
      </DialogActions>
    </Dialog>
  );
}

function StatusChip({ status }: { status: string }) {
  const color = status === "ACTIVE" ? "success" : status === "DEGRADED" ? "warning" : "default";
  return <Chip size="small" color={color} variant="outlined" label={status} />;
}

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "-";
}
