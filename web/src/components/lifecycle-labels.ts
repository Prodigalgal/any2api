import type { AccountActivationResult } from "@/lib/api";

export function accountActivationActionLabel(action: AccountActivationResult["action"]) {
  return ({
    PROBE: "真实探测",
    REAUTHENTICATE: "重新认证后探测",
    DAILY_CHECKIN: "每日签到",
  } as Record<AccountActivationResult["action"], string>)[action];
}
