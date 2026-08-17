import type {
  CaptchaAiMode,
  RegistrationProxyPolicy,
  RegistrationScheduleType,
} from "@/lib/api";

const jobStatuses: Record<string, string> = {
  PENDING: "等待执行",
  RUNNING: "执行中",
  SUCCEEDED: "已成功",
  PARTIAL: "部分成功",
  FAILED: "已失败",
  CANCELLED: "已取消",
};

export function registrationJobStatusLabel(status: string) {
  return jobStatuses[status] ?? "未知状态";
}

export function registrationProxyPolicyLabel(policy: RegistrationProxyPolicy) {
  if (policy === "DIRECT") return "强制直连";
  if (policy === "REQUIRED_POOL") return "必须使用代理池";
  return "使用厂商绑定";
}

export function captchaModeLabel(mode: CaptchaAiMode) {
  if (mode === "AUTO") return "自动选择";
  if (mode === "EXTERNAL") return "外部服务";
  return "内置服务";
}

export function scheduleTypeLabel(type: RegistrationScheduleType) {
  return type === "ONCE" ? "仅执行一次" : "循环执行";
}

export function intervalLabel(minutes: number | null) {
  if (!minutes) return "单次";
  if (minutes % 1440 === 0) return `每 ${minutes / 1440} 天`;
  if (minutes % 60 === 0) return `每 ${minutes / 60} 小时`;
  return `每 ${minutes} 分钟`;
}
