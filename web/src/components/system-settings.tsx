"use client";

import { SaveOutlined } from "@mui/icons-material";
import { Alert, Box, Button, FormControlLabel, LinearProgress, MenuItem, Stack, Switch, TextField, ToggleButton, ToggleButtonGroup, Typography } from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, type CaptchaAiMode, type RegistrationDefaults, type RegistrationProxyPolicy, type TempMailSettings } from "@/lib/api";
import { DataSurface, PageContainer, PageHeader } from "@/components/page-layout";

export function SystemSettingsPage() {
  const settings = useQuery({ queryKey: ["system-settings"], queryFn: api.systemSettings });
  return <PageContainer>
    <PageHeader title="系统设置" description="运行时配置保存后立即用于新任务，无需重启服务" />
    {settings.isLoading ? <LinearProgress /> : null}
    {settings.error ? <Alert severity="error">{settings.error.message}</Alert> : null}
    {settings.data ? <Stack spacing={2}>
      <TempMailForm initial={settings.data.tempMail} />
      <RegistrationDefaultsForm initial={settings.data.registrationDefaults} />
    </Stack> : null}
  </PageContainer>;
}

function TempMailForm({ initial }: { initial: TempMailSettings }) {
  const queryClient = useQueryClient();
  const [value, setValue] = useState(initial);
  const [domains, setDomains] = useState(initial.domains.join("\n"));
  const save = useMutation({
    mutationFn: () => api.updateTempMailSettings({ ...value, domains: domains.split(/[\n,]+/).map((item) => item.trim()).filter(Boolean) }),
    onSuccess: async (saved) => {
      setValue(saved); setDomains(saved.domains.join("\n"));
      await queryClient.invalidateQueries({ queryKey: ["system-settings"] });
    },
  });
  return <DataSurface>
    <SectionHeader title="Temp Mail" description="注册任务会从可用域名中按邮箱任务随机选择；任务可单独覆盖固定域名" action={<Button variant="contained" startIcon={<SaveOutlined />} disabled={save.isPending} onClick={() => save.mutate()}>保存邮箱设置</Button>} />
    <Box sx={{ p: 2.5 }}>
      {save.error ? <Alert severity="error" sx={{ mb: 2 }}>{save.error.message}</Alert> : null}
      {save.isSuccess ? <Alert severity="success" sx={{ mb: 2 }}>Temp Mail 设置已生效</Alert> : null}
      <Box sx={{ display: "grid", gridTemplateColumns: "1.4fr 1fr 1fr", gap: 2 }}>
        <TextField label="API URL" value={value.apiBase} onChange={(event) => setValue({ ...value, apiBase: event.target.value })} />
        <TextField label="管理员密码" value={value.adminPassword} onChange={(event) => setValue({ ...value, adminPassword: event.target.value })} />
        <TextField label="站点访问密码" value={value.sitePassword} onChange={(event) => setValue({ ...value, sitePassword: event.target.value })} />
        <TextField multiline minRows={4} label="可用邮箱域名（每行一个）" value={domains} onChange={(event) => setDomains(event.target.value)} sx={{ gridColumn: "span 2" }} />
        <Stack spacing={2}>
          <TextField label="轮询间隔（秒）" type="number" value={value.pollSeconds} onChange={(event) => setValue({ ...value, pollSeconds: Number(event.target.value) })} slotProps={{ htmlInput: { min: 1, max: 60, step: 0.5 } }} />
          <TextField label="邮件等待上限（秒）" type="number" value={value.messageTimeoutSeconds} onChange={(event) => setValue({ ...value, messageTimeoutSeconds: Number(event.target.value) })} slotProps={{ htmlInput: { min: 30, max: 1800 } }} />
          <TextField label="单次请求超时（秒）" type="number" value={value.requestTimeoutSeconds} onChange={(event) => setValue({ ...value, requestTimeoutSeconds: Number(event.target.value) })} slotProps={{ htmlInput: { min: 5, max: 300 } }} />
        </Stack>
      </Box>
    </Box>
  </DataSurface>;
}

function RegistrationDefaultsForm({ initial }: { initial: RegistrationDefaults }) {
  const queryClient = useQueryClient();
  const [value, setValue] = useState(initial);
  const save = useMutation({ mutationFn: () => api.updateRegistrationDefaults(value), onSuccess: async (saved) => { setValue(saved); await queryClient.invalidateQueries({ queryKey: ["system-settings"] }); } });
  return <DataSurface>
    <SectionHeader title="注册任务默认值" description="新建任务自动带入，单个任务仍可覆盖" action={<Button variant="contained" startIcon={<SaveOutlined />} disabled={save.isPending} onClick={() => save.mutate()}>保存任务默认值</Button>} />
    <Box sx={{ p: 2.5 }}>
      {save.error ? <Alert severity="error" sx={{ mb: 2 }}>{save.error.message}</Alert> : null}
      {save.isSuccess ? <Alert severity="success" sx={{ mb: 2 }}>注册默认参数已生效</Alert> : null}
      <Box sx={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 2 }}>
        <NumberField label="目标成功数" value={value.target} min={1} max={1000} onChange={(target) => setValue({ ...value, target })} />
        <NumberField label="最大邮箱任务数" value={value.maxAttempts} min={value.target} max={value.target * 10} onChange={(maxAttempts) => setValue({ ...value, maxAttempts })} />
        <NumberField label="并发数" value={value.concurrency} min={1} max={8} onChange={(concurrency) => setValue({ ...value, concurrency })} />
        <NumberField label="浏览器流程重试 / 邮箱" value={value.flowMaxAttempts} min={1} max={10} onChange={(flowMaxAttempts) => setValue({ ...value, flowMaxAttempts })} />
        <NumberField label="任务启动间隔（秒）" value={value.attemptIntervalSeconds} min={0} max={3600} onChange={(attemptIntervalSeconds) => setValue({ ...value, attemptIntervalSeconds })} />
        <NumberField label="轮次间隔（秒）" value={value.roundIntervalSeconds} min={0} max={86400} onChange={(roundIntervalSeconds) => setValue({ ...value, roundIntervalSeconds })} />
        <NumberField label="单邮箱任务超时（秒）" value={value.attemptTimeoutSeconds} min={60} max={3600} onChange={(attemptTimeoutSeconds) => setValue({ ...value, attemptTimeoutSeconds })} />
        <NumberField label="连续失败轮次上限" value={value.maxConsecutiveFailureBatches} min={1} max={20} onChange={(maxConsecutiveFailureBatches) => setValue({ ...value, maxConsecutiveFailureBatches })} />
        <TextField select label="代理策略" value={value.proxyPolicy} onChange={(event) => setValue({ ...value, proxyPolicy: event.target.value as RegistrationProxyPolicy })}><MenuItem value="PROVIDER_DEFAULT">使用厂商绑定</MenuItem><MenuItem value="DIRECT">强制直连</MenuItem><MenuItem value="REQUIRED_POOL">必须使用代理池</MenuItem></TextField>
        <FormControlLabel control={<Switch checked={value.headless} onChange={(event) => setValue({ ...value, headless: event.target.checked })} />} label="无头浏览器" />
        <FormControlLabel control={<Switch checked={value.aiCaptchaEnabled} onChange={(event) => setValue({ ...value, aiCaptchaEnabled: event.target.checked })} />} label="AI 打码" />
        <ToggleButtonGroup exclusive size="small" value={value.aiCaptchaMode} disabled={!value.aiCaptchaEnabled} onChange={(_, mode: CaptchaAiMode | null) => mode && setValue({ ...value, aiCaptchaMode: mode })}><ToggleButton value="AUTO">自动</ToggleButton><ToggleButton value="INTERNAL">内置</ToggleButton><ToggleButton value="EXTERNAL">外置</ToggleButton></ToggleButtonGroup>
      </Box>
    </Box>
  </DataSurface>;
}

function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (value: number) => void }) { return <TextField label={label} type="number" value={value} onChange={(event) => onChange(Number(event.target.value))} slotProps={{ htmlInput: { min, max } }} />; }
function SectionHeader({ title, description, action }: { title: string; description: string; action: React.ReactNode }) { return <Box sx={{ minHeight: 62, px: 2.5, py: 1.25, borderBottom: 1, borderColor: "divider", display: "flex", alignItems: "center" }}><Box><Typography sx={{ fontSize: 13.5, fontWeight: 750 }}>{title}</Typography><Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{description}</Typography></Box><Box sx={{ flex: 1 }} />{action}</Box>; }
