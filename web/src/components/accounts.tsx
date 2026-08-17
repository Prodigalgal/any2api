"use client";

import {
  AddOutlined,
  DeleteOutlineOutlined,
  FilterAltOffOutlined,
  LoginOutlined,
  ManageSearchOutlined,
  MonitorHeartOutlined,
  MoreVertOutlined,
  PlayCircleOutlineOutlined,
  RefreshOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  LinearProgress,
  Menu,
  MenuItem,
  Skeleton,
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
  Tooltip,
  Typography,
} from "@mui/material";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { memo, useEffect, useMemo, useState, type MouseEvent } from "react";
import {
  api,
  providerOptions,
  type Account,
  type AccountExpiryFilter,
  type ProviderOption,
} from "@/lib/api";
import {
  DataSurface,
  PageContainer,
  PageHeader,
  ToolbarSurface,
} from "@/components/page-layout";
import { OperationEventsDialog } from "@/components/operation-events-dialog";
import { AccountDetailDialog } from "@/components/account-detail-dialog";
import { AccountProbeDialog } from "@/components/account-probe-dialog";

const pageSizes = [25, 50, 100];
const statusOptions = [
  ["ACTIVE", "正常"],
  ["PENDING", "待就绪"],
  ["DEGRADED", "异常"],
  ["BANNED", "封禁"],
  ["DISABLED", "停用"],
  ["EXPIRED", "过期"],
] as const;
const expiryOptions: Array<[AccountExpiryFilter, string]> = [
  ["ANY", "全部到期状态"],
  ["VALID", "有效或长期"],
  ["EXPIRING_SOON", "7 天内到期"],
  ["EXPIRED", "已经到期"],
  ["NEVER", "未设置到期"],
];

export function Accounts() {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState("");
  const [status, setStatus] = useState("");
  const [enabled, setEnabled] = useState("");
  const [expiry, setExpiry] = useState<AccountExpiryFilter>("ANY");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [importOpen, setImportOpen] = useState(false);
  const [commandAccount, setCommandAccount] = useState<Account | null>(null);
  const [commandAnchor, setCommandAnchor] = useState<HTMLElement | null>(null);
  const [traceAccount, setTraceAccount] = useState<Account | null>(null);
  const [detailAccount, setDetailAccount] = useState<Account | null>(null);
  const [probeAccount, setProbeAccount] = useState<Account | null>(null);
  const [activationNotice, setActivationNotice] = useState("");
  const debouncedSearch = useDebouncedValue(search.trim(), 300);

  const catalog = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providers = providerOptions(catalog.data);
  const providerNames = useMemo(() => new Map(providers), [providers]);
  const probeProviders = useMemo(() => new Set(
    (catalog.data?.data ?? [])
      .filter((item) => item.configured)
      .map((item) => item.id),
  ), [catalog.data]);
  const reauthenticationProviders = useMemo(() => new Set(
    (catalog.data?.data ?? [])
      .filter((item) => item.lifecycleOperations.includes("reauthenticate"))
      .map((item) => item.id),
  ), [catalog.data]);
  const accounts = useQuery({
    queryKey: ["accounts-page", provider, status, enabled, expiry, debouncedSearch, page, pageSize],
    queryFn: () => api.accountPage({
      provider: provider || undefined,
      status: status || undefined,
      enabled: enabled ? enabled === "true" : undefined,
      query: debouncedSearch || undefined,
      expiry,
      page,
      size: pageSize,
    }),
    placeholderData: keepPreviousData,
  });

  const invalidateAccounts = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["accounts-page"] }),
      queryClient.invalidateQueries({ queryKey: ["admin-providers"] }),
    ]);
  };
  const update = useMutation({
    mutationFn: ({ account, nextEnabled }: { account: Account; nextEnabled: boolean }) =>
      api.updateAccount(account.id, {
        enabled: nextEnabled,
        status: nextEnabled ? "ACTIVE" : "DISABLED",
      }),
    onSuccess: invalidateAccounts,
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteAccount(id),
    onSuccess: async () => {
      if ((accounts.data?.items.length ?? 0) === 1 && page > 0) setPage((current) => current - 1);
      await invalidateAccounts();
    },
  });
  const reauthenticate = useMutation({
    mutationFn: (id: string) => api.reauthenticateAccount(id),
    onSuccess: invalidateAccounts,
  });
  const activate = useMutation({
    mutationFn: (id: string) => api.activateAccount(id),
    onSuccess: async (result) => {
      setActivationNotice(
        `${result.providerId} 激活任务已排队：${result.action === "PROBE" ? "真实探测" : "重新认证后探测"}`,
      );
      await invalidateAccounts();
    },
  });
  const commands = useQuery({
    queryKey: ["account-commands", commandAccount?.id],
    queryFn: () => api.accountCommands(commandAccount!.id),
    enabled: Boolean(commandAccount),
  });
  const executeCommand = useMutation({
    mutationFn: ({ accountId, command }: { accountId: string; command: string }) =>
      api.executeAccountCommand(accountId, command),
    onSuccess: async () => {
      setCommandAnchor(null);
      setCommandAccount(null);
      await invalidateAccounts();
    },
  });

  const openCommands = (event: MouseEvent<HTMLElement>, account: Account) => {
    setCommandAnchor(event.currentTarget);
    setCommandAccount(account);
  };
  const filtersActive = Boolean(provider || status || enabled || expiry !== "ANY" || search);
  const error = accounts.error ?? commands.error ?? executeCommand.error
    ?? update.error ?? remove.error ?? reauthenticate.error ?? activate.error;

  return (
    <PageContainer>
      <PageHeader
        title="账号池"
        description="按厂商隔离管理账号、运行状态与生命周期凭据"
        actions={
          <>
            <Tooltip title="刷新账号">
              <span>
                <IconButton
                  aria-label="刷新账号"
                  disabled={accounts.isFetching}
                  onClick={() => accounts.refetch()}
                  sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
                >
                  <RefreshOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Button variant="contained" startIcon={<AddOutlined />} onClick={() => setImportOpen(true)}>
              导入账号
            </Button>
          </>
        }
      />

      <ToolbarSurface>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: {
              xs: "1fr",
              sm: "minmax(260px, 1.5fr) repeat(2, minmax(150px, 0.8fr)) 40px",
              xl: "minmax(240px, 1.6fr) repeat(4, minmax(150px, 0.8fr)) 40px",
            },
            gap: 1.25,
            alignItems: "center",
          }}
        >
          <TextField
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            placeholder="搜索上游账号、邮箱或最近错误"
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlined sx={{ fontSize: 18, color: "text.secondary" }} />
                  </InputAdornment>
                ),
                inputProps: { "aria-label": "搜索账号" },
              },
            }}
          />
          <TextField
            select
            label="厂商"
            value={provider}
            onChange={(event) => {
              setProvider(event.target.value);
              setPage(0);
            }}
          >
            <MenuItem value="">全部厂商</MenuItem>
            {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
          </TextField>
          <TextField
            select
            label="状态"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
          >
            <MenuItem value="">全部状态</MenuItem>
            {statusOptions.map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}
          </TextField>
          <TextField
            select
            label="启用状态"
            value={enabled}
            onChange={(event) => {
              setEnabled(event.target.value);
              setPage(0);
            }}
          >
            <MenuItem value="">全部账号</MenuItem>
            <MenuItem value="true">仅启用</MenuItem>
            <MenuItem value="false">仅停用</MenuItem>
          </TextField>
          <TextField
            select
            label="到期状态"
            value={expiry}
            onChange={(event) => {
              setExpiry(event.target.value as AccountExpiryFilter);
              setPage(0);
            }}
          >
            {expiryOptions.map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}
          </TextField>
          <Tooltip title="清空筛选">
            <span>
              <IconButton
                aria-label="清空筛选"
                disabled={!filtersActive}
                onClick={() => {
                  setProvider("");
                  setStatus("");
                  setEnabled("");
                  setExpiry("ANY");
                  setSearch("");
                  setPage(0);
                }}
              >
                <FilterAltOffOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
        </Box>
      </ToolbarSurface>

      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}
      {activationNotice ? (
        <Alert severity="info" onClose={() => setActivationNotice("")} sx={{ mb: 2 }}>
          {activationNotice}
        </Alert>
      ) : null}

      <DataSurface>
        <LinearProgress
          sx={{
            position: "absolute",
            inset: "0 0 auto",
            zIndex: 3,
            height: 2,
            visibility: accounts.isFetching ? "visible" : "hidden",
          }}
        />
        <Box sx={{ px: 1.75, height: 44, display: "flex", alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
          <Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>账号列表</Typography>
          <Typography color="text.secondary" sx={{ ml: 1, fontSize: 12 }}>
            {accounts.data ? `${accounts.data.totalElements.toLocaleString("zh-CN")} 个结果` : "正在读取"}
          </Typography>
        </Box>
        <TableContainer sx={{ height: { xs: "auto", md: "calc(100vh - 386px)" }, minHeight: 340, maxHeight: { xs: 560, xl: 690 } }}>
          <Table stickyHeader size="small" sx={{ tableLayout: "fixed", minWidth: 940 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: 100 }}>厂商</TableCell>
                <TableCell sx={{ width: 135 }}>上游账号</TableCell>
                <TableCell sx={{ width: 170 }}>邮箱</TableCell>
                <TableCell sx={{ width: 90 }}>状态</TableCell>
                <TableCell align="right" sx={{ width: 155 }}>请求 / 成功 / 失败</TableCell>
                <TableCell sx={{ width: 120 }}>到期时间</TableCell>
                <TableCell align="center" sx={{ width: 65 }}>启用</TableCell>
                <TableCell align="right" sx={{ width: 135 }}>操作</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {accounts.isLoading ? <LoadingRows /> : null}
              {(accounts.data?.items ?? []).map((account) => (
                <AccountRow
                  key={account.id}
                  account={account}
                  providerName={providerNames.get(account.providerId) ?? account.providerId}
                  updating={update.isPending && update.variables?.account.id === account.id}
                  removing={remove.isPending && remove.variables === account.id}
                  reauthenticating={reauthenticate.isPending && reauthenticate.variables === account.id}
                  activating={activate.isPending && activate.variables === account.id}
                  probeSupported={probeProviders.has(account.providerId)}
                  reauthenticationSupported={reauthenticationProviders.has(account.providerId)}
                  onOpenDetails={() => setDetailAccount(account)}
                  onToggle={(nextEnabled) => {
                    if (nextEnabled) activate.mutate(account.id);
                    else update.mutate({ account, nextEnabled: false });
                  }}
                  onCommands={(event) => openCommands(event, account)}
                  onReauthenticate={() => reauthenticate.mutate(account.id)}
                  onActivate={() => activate.mutate(account.id)}
                  onProbe={() => setProbeAccount(account)}
                  onEvents={() => setTraceAccount(account)}
                  onDelete={() => {
                    if (window.confirm(`删除 ${account.providerId}/${account.externalId}？`)) {
                      remove.mutate(account.id);
                    }
                  }}
                />
              ))}
              {!accounts.isLoading && (accounts.data?.items.length ?? 0) === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} align="center" sx={{ height: 240, color: "text.secondary" }}>
                    <FilterAltOffOutlined sx={{ display: "block", mx: "auto", mb: 1, fontSize: 24, color: "#95a2a7" }} />
                    没有符合当前条件的账号
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={accounts.data?.totalElements ?? 0}
          page={page}
          rowsPerPage={pageSize}
          rowsPerPageOptions={pageSizes}
          labelRowsPerPage="每页"
          labelDisplayedRows={({ from, to, count }) => `${from}-${to} / ${count}`}
          onPageChange={(_, nextPage) => setPage(nextPage)}
          onRowsPerPageChange={(event) => {
            setPageSize(Number(event.target.value));
            setPage(0);
          }}
        />
      </DataSurface>

      <Menu
        anchorEl={commandAnchor}
        open={Boolean(commandAnchor)}
        onClose={() => {
          setCommandAnchor(null);
          setCommandAccount(null);
        }}
      >
        {commands.isLoading ? <MenuItem disabled>正在加载...</MenuItem> : null}
        {!commands.isLoading && (commands.data?.length ?? 0) === 0 ? (
          <MenuItem disabled>该厂商没有账号操作</MenuItem>
        ) : null}
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
        onImported={async () => {
          setImportOpen(false);
          setPage(0);
          await invalidateAccounts();
        }}
      />
      {traceAccount ? (
        <OperationEventsDialog
          open
          title={`${traceAccount.providerId} · ${traceAccount.externalId}`}
          queryKey={["account-events", traceAccount.id]}
          load={() => api.accountEvents(traceAccount.id)}
          onClose={() => setTraceAccount(null)}
        />
      ) : null}
      {detailAccount ? (
        <AccountDetailDialog
          account={detailAccount}
          providerName={providerNames.get(detailAccount.providerId) ?? detailAccount.providerId}
          reauthenticationSupported={reauthenticationProviders.has(detailAccount.providerId)}
          onClose={() => setDetailAccount(null)}
          onProbe={() => setProbeAccount(detailAccount)}
        />
      ) : null}
      {probeAccount ? (
        <AccountProbeDialog
          account={probeAccount}
          providerName={providerNames.get(probeAccount.providerId) ?? probeAccount.providerId}
          onClose={() => setProbeAccount(null)}
        />
      ) : null}
    </PageContainer>
  );
}

const AccountRow = memo(function AccountRow({
  account,
  providerName,
  updating,
  removing,
  reauthenticating,
  activating,
  probeSupported,
  reauthenticationSupported,
  onOpenDetails,
  onToggle,
  onCommands,
  onReauthenticate,
  onActivate,
  onProbe,
  onEvents,
  onDelete,
}: {
  account: Account;
  providerName: string;
  updating: boolean;
  removing: boolean;
  reauthenticating: boolean;
  activating: boolean;
  probeSupported: boolean;
  reauthenticationSupported: boolean;
  onOpenDetails: () => void;
  onToggle: (enabled: boolean) => void;
  onCommands: (event: MouseEvent<HTMLElement>) => void;
  onReauthenticate: () => void;
  onActivate: () => void;
  onProbe: () => void;
  onEvents: () => void;
  onDelete: () => void;
}) {
  return (
    <TableRow hover>
      <TableCell>
        <Typography noWrap sx={{ fontSize: 12.5, fontWeight: 700 }}>{providerName}</Typography>
        {providerName !== account.providerId ? (
          <Typography noWrap color="text.secondary" sx={{ fontFamily: "ui-monospace, monospace", fontSize: 10.5 }}>
            {account.providerId}
          </Typography>
        ) : null}
      </TableCell>
      <TableCell>
        <ButtonBase
          onClick={onOpenDetails}
          title={account.externalId}
          sx={{ maxWidth: "100%", justifyContent: "flex-start", color: "text.primary", fontFamily: "ui-monospace, monospace", fontSize: 11.5, borderRadius: 0.5, "&:hover": { color: "primary.main", textDecoration: "underline" } }}
        >
          <Box component="span" sx={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{account.externalId}</Box>
        </ButtonBase>
      </TableCell>
      <TableCell>
        <Typography noWrap title={account.email ?? ""} sx={{ fontSize: 12.5 }}>
          {account.email || "-"}
        </Typography>
      </TableCell>
      <TableCell><StatusChip status={account.status} error={account.lastError} /></TableCell>
      <TableCell align="right"><CounterTriplet account={account} /></TableCell>
      <TableCell>
        <Typography noWrap sx={{ fontVariantNumeric: "tabular-nums", fontSize: 11.5 }}>
          {formatTime(account.expiresAt)}
        </Typography>
      </TableCell>
      <TableCell align="center">
        <Switch
          size="small"
          checked={account.enabled}
          disabled={updating || activating || (!account.enabled && account.status === "BANNED")}
          onChange={(_, nextEnabled) => onToggle(nextEnabled)}
          slotProps={{ input: { "aria-label": `${account.externalId} 启用状态` } }}
        />
      </TableCell>
      <TableCell align="right">
        <Stack direction="row" spacing={0.25} sx={{ justifyContent: "flex-end" }}>
          <Tooltip title="查看生命周期轨迹">
            <IconButton size="small" aria-label="查看生命周期轨迹" onClick={onEvents}>
              <ManageSearchOutlined sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Tooltip title="厂商账号操作">
            <span>
              <IconButton size="small" disabled={!account.enabled} onClick={onCommands}>
                <MoreVertOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
          <Tooltip title={probeSupported ? "立即测活" : "该厂商当前不可用"}>
            <span>
              <IconButton
                size="small"
                aria-label="立即测活"
                disabled={!probeSupported}
                onClick={onProbe}
              >
                <MonitorHeartOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
          {!account.enabled ? (
            <Tooltip title="激活入池">
              <span>
                <IconButton size="small" disabled={activating || account.status === "BANNED"} onClick={onActivate}>
                  <PlayCircleOutlineOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
          ) : reauthenticationSupported ? (
            <Tooltip title="重新认证">
              <span>
                <IconButton size="small" disabled={reauthenticating} onClick={onReauthenticate}>
                  <LoginOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
          ) : null}
          <Tooltip title="删除账号">
            <span>
              <IconButton size="small" color="error" disabled={removing} onClick={onDelete}>
                <DeleteOutlineOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      </TableCell>
    </TableRow>
  );
});

function CounterTriplet({ account }: { account: Account }) {
  return (
    <Box
      sx={{
        ml: "auto",
        width: 156,
        display: "grid",
        gridTemplateColumns: "repeat(3, 1fr)",
        fontFamily: "ui-monospace, monospace",
        fontSize: 11.5,
        fontVariantNumeric: "tabular-nums",
      }}
    >
      <span>{account.requestCount}</span>
      <Box component="span" sx={{ color: "success.main" }}>{account.successCount}</Box>
      <Box component="span" sx={{ color: account.failureCount ? "error.main" : "text.secondary" }}>
        {account.failureCount}
      </Box>
    </Box>
  );
}

function LoadingRows() {
  return Array.from({ length: 8 }, (_, index) => (
    <TableRow key={index}>
      {Array.from({ length: 8 }, (__, cell) => (
        <TableCell key={cell}><Skeleton animation="wave" height={18} /></TableCell>
      ))}
    </TableRow>
  ));
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
  onImported: () => void | Promise<void>;
}) {
  const [providerId, setProviderId] = useState("");
  const [externalId, setExternalId] = useState("");
  const [email, setEmail] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [credential, setCredential] = useState("{\n  \"token\": \"\"\n}");
  const mutation = useMutation({
    mutationFn: () => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(credential);
      } catch {
        throw new Error("凭据不是有效 JSON");
      }
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
          {mutation.error ? <Alert severity="error">{mutation.error.message}</Alert> : null}
          <TextField select label="厂商" value={providerId} onChange={(event) => setProviderId(event.target.value)}>
            {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
          </TextField>
          <TextField label="上游账号 ID" value={externalId} onChange={(event) => setExternalId(event.target.value)} />
          <TextField label="邮箱" value={email} onChange={(event) => setEmail(event.target.value)} />
          <TextField
            label="账号到期时间"
            type="datetime-local"
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="凭据 JSON"
            value={credential}
            onChange={(event) => setCredential(event.target.value)}
            multiline
            minRows={8}
            slotProps={{ htmlInput: { spellCheck: false } }}
            sx={{ "& textarea": { fontFamily: "ui-monospace, monospace", fontSize: 12 } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button
          variant="contained"
          onClick={() => mutation.mutate()}
          disabled={!providerId || !externalId || mutation.isPending}
        >
          保存
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function StatusChip({ status, error }: { status: string; error: string | null }) {
  const details: Record<string, { label: string; color: "success" | "warning" | "error" | "default" }> = {
    ACTIVE: { label: "正常", color: "success" },
    PENDING: { label: "待就绪", color: "warning" },
    DEGRADED: { label: "异常", color: "error" },
    BANNED: { label: "封禁", color: "error" },
    DISABLED: { label: "停用", color: "default" },
    EXPIRED: { label: "过期", color: "default" },
  };
  const value = details[status] ?? { label: status, color: "default" as const };
  const chip = <Chip size="small" color={value.color} variant="outlined" label={value.label} />;
  return error ? <Tooltip title={error}>{chip}</Tooltip> : chip;
}

function formatTime(value: string | null) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))
    : "长期";
}

function useDebouncedValue<T>(value: T, delay: number) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timeout);
  }, [delay, value]);
  return debounced;
}
