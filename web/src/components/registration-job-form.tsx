"use client";

import {
  Box,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import type {
  CaptchaAiMode,
  ProviderOption,
  RegistrationJobFormValue,
  RegistrationProxyPolicy,
  SystemSettings,
} from "@/lib/api";

export function registrationJobDefaults(
  settings?: SystemSettings,
  existing?: Partial<RegistrationJobFormValue>,
): RegistrationJobFormValue {
  const defaults = settings?.registrationDefaults;
  return {
    providerId: existing?.providerId ?? "",
    target: existing?.target ?? defaults?.target ?? 1,
    maxAttempts: existing?.maxAttempts ?? defaults?.maxAttempts ?? 3,
    concurrency: existing?.concurrency ?? defaults?.concurrency ?? 1,
    attemptIntervalSeconds: existing?.attemptIntervalSeconds
      ?? defaults?.attemptIntervalSeconds ?? 0,
    roundIntervalSeconds: existing?.roundIntervalSeconds
      ?? defaults?.roundIntervalSeconds ?? 5,
    attemptTimeoutSeconds: existing?.attemptTimeoutSeconds
      ?? defaults?.attemptTimeoutSeconds ?? 2100,
    flowMaxAttempts: existing?.flowMaxAttempts ?? defaults?.flowMaxAttempts ?? 3,
    maxConsecutiveFailureBatches: existing?.maxConsecutiveFailureBatches
      ?? defaults?.maxConsecutiveFailureBatches ?? 5,
    proxyPolicy: existing?.proxyPolicy ?? defaults?.proxyPolicy ?? "PROVIDER_DEFAULT",
    headless: existing?.headless ?? defaults?.headless ?? true,
    mailDomain: existing?.mailDomain ?? null,
    aiCaptchaEnabled: existing?.aiCaptchaEnabled ?? defaults?.aiCaptchaEnabled ?? true,
    aiCaptchaMode: existing?.aiCaptchaMode ?? defaults?.aiCaptchaMode ?? "INTERNAL",
    idempotencyKey: null,
  };
}

export function registrationJobValidation(value: RegistrationJobFormValue) {
  if (!value.providerId) return "请选择厂商";
  if (value.target < 1 || value.target > 1000) return "目标成功数应为 1 至 1000";
  if (value.maxAttempts < value.target || value.maxAttempts > value.target * 10) {
    return "最大邮箱任务数不能小于目标成功数，且不能超过目标的 10 倍";
  }
  if (value.concurrency < 1 || value.concurrency > 8) return "并发数应为 1 至 8";
  if (value.attemptIntervalSeconds < 0 || value.attemptIntervalSeconds > 3600) {
    return "任务启动间隔应为 0 至 3600 秒";
  }
  if (value.roundIntervalSeconds < 0 || value.roundIntervalSeconds > 86400) {
    return "轮次间隔应为 0 至 86400 秒";
  }
  if (value.attemptTimeoutSeconds < 60 || value.attemptTimeoutSeconds > 3600) {
    return "单邮箱任务超时应为 60 至 3600 秒";
  }
  if (value.flowMaxAttempts < 1 || value.flowMaxAttempts > 10) {
    return "同邮箱浏览器流程数应为 1 至 10";
  }
  if (value.maxConsecutiveFailureBatches < 1 || value.maxConsecutiveFailureBatches > 20) {
    return "连续失败轮次上限应为 1 至 20";
  }
  return null;
}

export function RegistrationJobFields({
  value,
  providers,
  settings,
  onChange,
}: {
  value: RegistrationJobFormValue;
  providers: ProviderOption[];
  settings?: SystemSettings;
  onChange: (value: RegistrationJobFormValue) => void;
}) {
  const patch = (next: Partial<RegistrationJobFormValue>) => onChange({ ...value, ...next });

  return (
    <Stack spacing={2.25}>
      <Typography variant="subtitle1">注册参数</Typography>
      <Box
        sx={{
          display: "grid",
          gridTemplateColumns: { xs: "1fr", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(3, minmax(0, 1fr))" },
          columnGap: 2,
          rowGap: 2,
          alignItems: "start",
        }}
      >
        <TextField
          select
          label="厂商"
          value={value.providerId}
          onChange={(event) => patch({ providerId: event.target.value })}
        >
          {providers.map(([id, name]) => <MenuItem key={id} value={id}>{name}</MenuItem>)}
        </TextField>
        <TextField
          label="目标成功数"
          type="number"
          value={value.target}
          onChange={(event) => patch({ target: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 1, max: 1000 } }}
        />
        <TextField
          label="最大邮箱任务数"
          type="number"
          value={value.maxAttempts}
          onChange={(event) => patch({ maxAttempts: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: value.target, max: value.target * 10 } }}
        />
        <TextField
          select
          label="并发数"
          value={value.concurrency}
          onChange={(event) => patch({ concurrency: Number(event.target.value) })}
        >
          {[1, 2, 3, 4, 6, 8].map((item) => <MenuItem key={item} value={item}>{item}</MenuItem>)}
        </TextField>
        <TextField
          label="任务启动间隔（秒）"
          type="number"
          value={value.attemptIntervalSeconds}
          onChange={(event) => patch({ attemptIntervalSeconds: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 0, max: 3600 } }}
        />
        <TextField
          label="轮次间隔（秒）"
          type="number"
          value={value.roundIntervalSeconds}
          onChange={(event) => patch({ roundIntervalSeconds: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 0, max: 86400 } }}
        />
        <TextField
          label="单邮箱任务超时（秒）"
          type="number"
          value={value.attemptTimeoutSeconds}
          onChange={(event) => patch({ attemptTimeoutSeconds: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 60, max: 3600 } }}
        />
        <TextField
          label="同邮箱浏览器流程数"
          type="number"
          value={value.flowMaxAttempts}
          onChange={(event) => patch({ flowMaxAttempts: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 1, max: 10 } }}
        />
        <TextField
          label="连续失败轮次上限"
          type="number"
          value={value.maxConsecutiveFailureBatches}
          onChange={(event) => patch({ maxConsecutiveFailureBatches: Number(event.target.value) })}
          slotProps={{ htmlInput: { min: 1, max: 20 } }}
        />
        <TextField
          select
          label="注册代理策略"
          value={value.proxyPolicy}
          onChange={(event) => patch({ proxyPolicy: event.target.value as RegistrationProxyPolicy })}
        >
          <MenuItem value="PROVIDER_DEFAULT">使用厂商绑定</MenuItem>
          <MenuItem value="DIRECT">强制直连</MenuItem>
          <MenuItem value="REQUIRED_POOL">必须使用代理池</MenuItem>
        </TextField>
        <TextField
          select
          label="邮箱域名"
          value={value.mailDomain ?? ""}
          onChange={(event) => patch({ mailDomain: event.target.value || null })}
        >
          <MenuItem value="">随机可用域名</MenuItem>
          {(settings?.tempMail.domains ?? []).map((domain) => (
            <MenuItem key={domain} value={domain}>{domain}</MenuItem>
          ))}
        </TextField>
        <FormControlLabel
          sx={{ minHeight: 40, m: 0 }}
          control={(
            <Switch
              checked={value.headless}
              onChange={(event) => patch({ headless: event.target.checked })}
            />
          )}
          label="使用无界面浏览器"
        />
      </Box>
      <Stack
        direction={{ xs: "column", sm: "row" }}
        spacing={{ xs: 1.25, sm: 3 }}
        sx={{ alignItems: { xs: "stretch", sm: "center" } }}
      >
        <FormControlLabel
          sx={{ m: 0 }}
          control={(
            <Switch
              checked={value.aiCaptchaEnabled}
              onChange={(event) => patch({ aiCaptchaEnabled: event.target.checked })}
            />
          )}
          label="启用 AI 验证码识别"
        />
        <ToggleButtonGroup
          exclusive
          size="small"
          value={value.aiCaptchaMode}
          disabled={!value.aiCaptchaEnabled}
          onChange={(_, next: CaptchaAiMode | null) => next && patch({ aiCaptchaMode: next })}
          aria-label="AI 验证码识别来源"
          sx={{ alignSelf: { xs: "stretch", sm: "auto" }, "& .MuiToggleButton-root": { flex: { xs: 1, sm: "initial" } } }}
        >
          <ToggleButton value="AUTO">自动选择</ToggleButton>
          <ToggleButton value="INTERNAL">内置服务</ToggleButton>
          <ToggleButton value="EXTERNAL">外部服务</ToggleButton>
        </ToggleButtonGroup>
      </Stack>
    </Stack>
  );
}
