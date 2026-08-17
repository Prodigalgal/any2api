"use client";

import {
  AddOutlined,
  CloseOutlined,
  DeleteOutlineOutlined,
  HistoryOutlined,
  RefreshOutlined,
  RestoreOutlined,
  SaveOutlined,
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
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  api,
  type ProviderRuntimeRuleDocument,
  type ProviderRuntimeRuleRevision,
  type ProviderRuntimeRuleState,
} from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

const providerNames: Record<string, string> = { mimo: "MiMo", glm: "GLM" };

export function ProviderRuntimeRules() {
  const queryClient = useQueryClient();
  const [selectedProvider, setSelectedProvider] = useState("");
  const [editing, setEditing] = useState<ProviderRuntimeRuleState | null>(null);
  const [rollbackRevision, setRollbackRevision] = useState<number | null>(null);
  const states = useQuery({
    queryKey: ["provider-runtime-rules"],
    queryFn: api.providerRuntimeRules,
  });
  const current = useMemo(
    () => states.data?.find((state) => state.providerId === selectedProvider)
      ?? states.data?.[0]
      ?? null,
    [selectedProvider, states.data],
  );

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ["provider-runtime-rules"] });
  };
  const discard = useMutation({
    mutationFn: (providerId: string) => api.discardProviderRuntimeRuleCandidate(providerId),
    onSuccess: invalidate,
  });
  const rollback = useMutation({
    mutationFn: ({ providerId, revision }: { providerId: string; revision: number }) => (
      api.rollbackProviderRuntimeRule(providerId, revision)
    ),
    onSuccess: async () => {
      setRollbackRevision(null);
      await invalidate();
    },
  });
  const error = states.error ?? discard.error ?? rollback.error;

  return (
    <PageContainer>
      <PageHeader
        title="运行时规则"
        description="官方页面语义映射、Build Canary 与版本回滚"
        actions={
          <>
            <Tooltip title="刷新运行时规则">
              <span>
                <IconButton
                  aria-label="刷新运行时规则"
                  onClick={() => void states.refetch()}
                  disabled={states.isFetching}
                  sx={{ border: 1, borderColor: "divider", bgcolor: "background.paper" }}
                >
                  <RefreshOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Button
              variant="contained"
              startIcon={<AddOutlined />}
              disabled={!current}
              onClick={() => current && setEditing(current)}
            >
              {current?.candidate ? "修订候选规则" : "新建候选规则"}
            </Button>
          </>
        }
      />

      {error ? <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert> : null}
      {current?.candidateStatus === "FAILED" ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          候选 revision {current.candidate?.revision} 未通过 Build Canary：
          {current.failureReason || "运行时发现失败"}
        </Alert>
      ) : null}

      <DataSurface>
        <LinearProgress sx={{ visibility: states.isFetching ? "visible" : "hidden" }} />
        <Tabs
          value={current?.providerId ?? false}
          onChange={(_, value: string) => setSelectedProvider(value)}
          sx={{ px: 1.5, borderBottom: 1, borderColor: "divider" }}
        >
          {(states.data ?? []).map((state) => (
            <Tab
              key={state.providerId}
              value={state.providerId}
              label={providerNames[state.providerId] ?? state.providerId}
            />
          ))}
        </Tabs>

        {current ? (
          <>
            <RuntimeSummary state={current} />
            <Box sx={{ px: 2, height: 46, display: "flex", alignItems: "center", borderTop: 1, borderBottom: 1, borderColor: "divider" }}>
              <HistoryOutlined sx={{ mr: 1, fontSize: 18, color: "text.secondary" }} />
              <Typography noWrap sx={{ fontWeight: 720, fontSize: 12.5 }}>Revision 历史</Typography>
              <Typography noWrap color="text.secondary" sx={{ display: { xs: "none", sm: "block" }, ml: 1, fontSize: 11.5 }}>
                最近 {current.revisions.length} 个不可变版本
              </Typography>
              <Box sx={{ flex: 1 }} />
              {current.candidate ? (
                <>
                  <Tooltip title="废弃候选规则">
                    <span>
                      <IconButton
                        aria-label="废弃候选规则"
                        disabled={discard.isPending}
                        onClick={() => discard.mutate(current.providerId)}
                        sx={{ display: { sm: "none" } }}
                      >
                        <DeleteOutlineOutlined sx={{ fontSize: 18 }} />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Button
                    color="inherit"
                    size="small"
                    startIcon={<DeleteOutlineOutlined />}
                    disabled={discard.isPending}
                    onClick={() => discard.mutate(current.providerId)}
                    sx={{ display: { xs: "none", sm: "inline-flex" }, whiteSpace: "nowrap" }}
                  >
                    废弃候选
                  </Button>
                </>
              ) : null}
            </Box>
            <RevisionTable
              state={current}
              onRollback={(revision) => setRollbackRevision(revision)}
            />
          </>
        ) : (
          <Box sx={{ minHeight: 320, display: "grid", placeItems: "center" }}>
            <Typography color="text.secondary">暂无支持热更新的厂商</Typography>
          </Box>
        )}
      </DataSurface>

      {editing ? (
        <RuntimeRuleDialog
          state={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await invalidate();
          }}
        />
      ) : null}
      {current && rollbackRevision !== null ? (
        <Dialog open onClose={() => setRollbackRevision(null)} maxWidth="xs" fullWidth>
          <DialogTitle>创建回滚候选</DialogTitle>
          <DialogContent>
            <Alert severity="info">
              revision {rollbackRevision} 会复制为新的候选版本，并在真实请求通过 Canary 后生效。
            </Alert>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setRollbackRevision(null)}>取消</Button>
            <Button
              variant="contained"
              startIcon={<RestoreOutlined />}
              disabled={rollback.isPending}
              onClick={() => rollback.mutate({
                providerId: current.providerId,
                revision: rollbackRevision,
              })}
            >
              创建候选
            </Button>
          </DialogActions>
        </Dialog>
      ) : null}
    </PageContainer>
  );
}

function RuntimeSummary({ state }: { state: ProviderRuntimeRuleState }) {
  const status = state.candidateStatus === "PENDING"
    ? { label: "等待 Canary", color: "warning" as const }
    : state.candidateStatus === "FAILED"
      ? { label: "Canary 失败", color: "error" as const }
      : { label: "已生效", color: "success" as const };
  return (
    <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(4, minmax(0, 1fr))" } }}>
      <SummaryItem label="当前 Revision" value={`r${state.active.revision}`} />
      <SummaryItem
        label="候选状态"
        value={<Chip size="small" color={status.color} variant="outlined" label={status.label} />}
      />
      <SummaryItem
        label="当前 Build"
        value={state.activeBuildId ? state.activeBuildId.slice(0, 12) : "等待首次观测"}
        title={state.activeBuildId ?? undefined}
        mono
      />
      <SummaryItem
        label="Last-known-good"
        value={state.lastKnownGoodRevision ? `r${state.lastKnownGoodRevision}` : "当前基线"}
      />
    </Box>
  );
}

function SummaryItem({ label, value, title, mono = false }: {
  label: string;
  value: React.ReactNode;
  title?: string;
  mono?: boolean;
}) {
  return (
    <Box sx={{ px: 2, py: 1.75, minHeight: 78, borderRight: { lg: 1 }, borderBottom: { xs: 1, lg: 0 }, borderColor: "divider" }}>
      <Typography color="text.secondary" sx={{ fontSize: 10.5, mb: 0.75 }}>{label}</Typography>
      <Box title={title} sx={{ fontWeight: 720, fontSize: 13, fontFamily: mono ? "ui-monospace, monospace" : "inherit", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
        {value}
      </Box>
    </Box>
  );
}

function RevisionTable({ state, onRollback }: {
  state: ProviderRuntimeRuleState;
  onRollback: (revision: number) => void;
}) {
  return (
    <TableContainer sx={{ minHeight: 300, maxHeight: "calc(100vh - 390px)" }}>
      <Table stickyHeader size="small" sx={{ minWidth: 860 }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 110 }}>Revision</TableCell>
            <TableCell sx={{ width: 150 }}>状态</TableCell>
            <TableCell>Checksum</TableCell>
            <TableCell sx={{ width: 150 }}>会话 TTL</TableCell>
            <TableCell sx={{ width: 190 }}>创建时间</TableCell>
            <TableCell align="right" sx={{ width: 72 }}>操作</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {state.revisions.map((revision) => (
            <TableRow key={revision.revision} hover>
              <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontWeight: 720 }}>r{revision.revision}</TableCell>
              <TableCell><RevisionStatus state={state} revision={revision} /></TableCell>
              <TableCell sx={{ fontFamily: "ui-monospace, monospace", fontSize: 11.5 }}>{revision.checksum.slice(0, 20)}</TableCell>
              <TableCell>{revision.rules.sessionMaxAgeSeconds} 秒</TableCell>
              <TableCell>{new Date(revision.createdAt).toLocaleString("zh-CN")}</TableCell>
              <TableCell align="right">
                <Tooltip title="以此版本创建回滚候选">
                  <span>
                    <IconButton
                      aria-label={`回滚到 revision ${revision.revision}`}
                      disabled={revision.revision === state.active.revision || revision.revision === state.candidate?.revision}
                      onClick={() => onRollback(revision.revision)}
                    >
                      <RestoreOutlined sx={{ fontSize: 17 }} />
                    </IconButton>
                  </span>
                </Tooltip>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function RevisionStatus({ state, revision }: { state: ProviderRuntimeRuleState; revision: ProviderRuntimeRuleRevision }) {
  if (revision.revision === state.active.revision) return <Chip size="small" color="success" label="当前生效" />;
  if (revision.revision === state.candidate?.revision) {
    return <Chip size="small" color={state.candidateStatus === "FAILED" ? "error" : "warning"} label="候选" />;
  }
  if (revision.revision === state.lastKnownGoodRevision) return <Chip size="small" variant="outlined" label="Last-known-good" />;
  return <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>历史版本</Typography>;
}

function RuntimeRuleDialog({ state, onClose, onSaved }: {
  state: ProviderRuntimeRuleState;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}) {
  const base = state.candidate?.rules ?? state.active.rules;
  const [sessionAge, setSessionAge] = useState(String(base.sessionMaxAgeSeconds));
  const [canaryTimeout, setCanaryTimeout] = useState(String(base.canaryTimeoutSeconds));
  const [buildMarkers, setBuildMarkers] = useState(base.buildAssetMarkers.join("\n"));
  const [discovery, setDiscovery] = useState(() => joinStringLists(base.discoveryMarkers));
  const [capabilities, setCapabilities] = useState(() => ({ ...base.capabilities }));
  const [endpoints, setEndpoints] = useState(() => ({ ...base.endpointPaths }));
  const mutation = useMutation({
    mutationFn: (value: ProviderRuntimeRuleDocument) => (
      api.createProviderRuntimeRuleCandidate(state.providerId, value)
    ),
    onSuccess: onSaved,
  });
  const value: ProviderRuntimeRuleDocument = {
    schemaVersion: 1,
    sessionMaxAgeSeconds: Number(sessionAge),
    canaryTimeoutSeconds: Number(canaryTimeout),
    buildAssetMarkers: lines(buildMarkers),
    discoveryMarkers: Object.fromEntries(
      Object.entries(discovery).map(([key, markers]) => [key, lines(markers)]),
    ),
    capabilities,
    endpointPaths: endpoints,
  };
  const invalid = !Number.isInteger(value.sessionMaxAgeSeconds)
    || value.sessionMaxAgeSeconds < 60
    || value.sessionMaxAgeSeconds > 86400
    || !Number.isInteger(value.canaryTimeoutSeconds)
    || value.canaryTimeoutSeconds < 5
    || value.canaryTimeoutSeconds > 300
    || value.buildAssetMarkers.length === 0
    || Object.values(value.discoveryMarkers).some((markers) => markers.length === 0)
    || Object.values(value.endpointPaths).some((path) => !path.startsWith("/") || path.includes("://"));

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: "flex", alignItems: "center" }}>
        {providerNames[state.providerId] ?? state.providerId} 候选规则
        <Box sx={{ flex: 1 }} />
        <IconButton aria-label="关闭" onClick={onClose}><CloseOutlined /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Alert severity="info" sx={{ mb: 2 }}>
          保存后不会立即切换。Automation 必须在当前官方 Build 上完成真实请求，候选规则才会生效。
        </Alert>
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" }, gap: 1.5 }}>
          <TextField label="会话最大存活秒数" type="number" value={sessionAge} onChange={(event) => setSessionAge(event.target.value)} slotProps={{ htmlInput: { min: 60, max: 86400 } }} />
          <TextField label="Canary 超时秒数" type="number" value={canaryTimeout} onChange={(event) => setCanaryTimeout(event.target.value)} slotProps={{ htmlInput: { min: 5, max: 300 } }} />
          <TextField sx={{ gridColumn: { sm: "1 / -1" } }} label="Build 静态资源标记（每行一个）" multiline minRows={2} value={buildMarkers} onChange={(event) => setBuildMarkers(event.target.value)} />
        </Box>
        <RuleSection title="模块发现标记">
          {Object.entries(discovery).map(([key, markers]) => (
            <TextField key={key} label={key} multiline minRows={2} value={markers} onChange={(event) => setDiscovery((current) => ({ ...current, [key]: event.target.value }))} />
          ))}
        </RuleSection>
        {Object.keys(capabilities).length > 0 ? (
          <RuleSection title="能力导出名">
            {Object.entries(capabilities).map(([key, capability]) => (
              <TextField key={key} label={key} value={capability} onChange={(event) => setCapabilities((current) => ({ ...current, [key]: event.target.value.trim() }))} />
            ))}
          </RuleSection>
        ) : null}
        <RuleSection title="同源端点">
          {Object.entries(endpoints).map(([key, endpoint]) => (
            <TextField key={key} label={key} value={endpoint} onChange={(event) => setEndpoints((current) => ({ ...current, [key]: event.target.value.trim() }))} />
          ))}
        </RuleSection>
        {mutation.error ? <Alert severity="error" sx={{ mt: 2 }}>{mutation.error.message}</Alert> : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>取消</Button>
        <Button variant="contained" startIcon={<SaveOutlined />} disabled={invalid || mutation.isPending} onClick={() => mutation.mutate(value)}>保存候选</Button>
      </DialogActions>
    </Dialog>
  );
}

function RuleSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Box component="section" sx={{ mt: 2.5 }}>
      <Typography sx={{ mb: 1.25, fontSize: 12.5, fontWeight: 720 }}>{title}</Typography>
      <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))" }, gap: 1.5 }}>
        {children}
      </Box>
    </Box>
  );
}

function lines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean))];
}

function joinStringLists(value: Record<string, string[]>): Record<string, string> {
  return Object.fromEntries(Object.entries(value).map(([key, markers]) => [key, markers.join("\n")]));
}
