"use client";

import {
  ArrowForwardOutlined,
  LockOutlined,
  PersonOutlined,
  SecurityOutlined,
  VisibilityOffOutlined,
  VisibilityOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { solvePow } from "@/lib/pow";
import styles from "./login-screen.module.css";

export function LoginScreen() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [mathAnswer, setMathAnswer] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const session = useQuery({
    queryKey: ["admin-session"],
    queryFn: api.session,
    retry: false,
  });
  const challenge = useQuery({
    queryKey: ["login-challenge"],
    queryFn: async ({ signal }) => {
      const value = await api.loginChallenge();
      const nonce = await solvePow(value.challengeToken, value.difficulty, signal);
      return { value, nonce };
    },
    retry: 1,
    staleTime: 5 * 60 * 1_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    enabled: session.isSuccess && session.data.authenticated === false,
  });
  const login = useMutation({
    mutationFn: () => api.login({
      username,
      password,
      challengeToken: challenge.data?.value.challengeToken,
      mathAnswer,
      powNonce: challenge.data?.nonce,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-session"] });
      router.replace("/");
    },
    onError: async () => {
      setMathAnswer("");
      await challenge.refetch();
    },
  });
  const ready = Boolean(challenge.data && username && password && mathAnswer);

  useEffect(() => {
    if (session.data?.authenticated) router.replace("/");
  }, [router, session.data]);

  return (
    <main className={styles.page}>
      <section className={styles.visual} aria-hidden="true">
        <div className={styles.lightRays}><i /><i /><i /></div>
        <div className={styles.surfaceTexture} />
        <div className={styles.visualHeader}>
          <div className={styles.visualMark}><span /><span /></div>
          <div>
            <Typography component="p">Any2API</Typography>
            <Typography component="span">运行中心</Typography>
          </div>
        </div>

        <div className={styles.topology}>
          <div className={styles.topologyGlow} />
          <div className={styles.topologyFrame} />
          <div className={styles.routeBus}>
            <i className={styles.packetOne} />
            <i className={styles.packetTwo} />
            <i className={styles.packetThree} />
          </div>
          <div className={`${styles.branch} ${styles.branchTopLeft}`} />
          <div className={`${styles.branch} ${styles.branchBottomLeft}`} />
          <div className={`${styles.branch} ${styles.branchTopRight}`} />
          <div className={`${styles.branch} ${styles.branchBottomRight}`} />
          <i className={`${styles.endpoint} ${styles.endpointOne}`} />
          <i className={`${styles.endpoint} ${styles.endpointTwo}`} />
          <i className={`${styles.endpoint} ${styles.endpointThree}`} />
          <i className={`${styles.endpoint} ${styles.endpointFour}`} />
          <div className={styles.hubReticle} />
          <div className={styles.hub}>
            <div className={styles.hubMark}><span /><span /></div>
            <div>
              <Typography component="p">ANY2API</Typography>
              <Typography component="span">路由已启用</Typography>
            </div>
          </div>
          <div className={styles.scanLine} />
        </div>

        <div className={styles.visualCopy}>
          <Typography component="h2">统一模型运行控制台</Typography>
          <Typography component="p">Any2API 控制平台</Typography>
        </div>
        <div className={styles.visualStatus}>
          <span><i /> 控制平台</span>
          <b>在线</b>
        </div>
      </section>

      <section
        className={styles.panel}
        onPointerMove={(event) => {
          const bounds = event.currentTarget.getBoundingClientRect();
          event.currentTarget.style.setProperty("--spotlight-x", `${event.clientX - bounds.left}px`);
          event.currentTarget.style.setProperty("--spotlight-y", `${event.clientY - bounds.top}px`);
        }}
        onPointerLeave={(event) => {
          event.currentTarget.style.setProperty("--spotlight-x", "50%");
          event.currentTarget.style.setProperty("--spotlight-y", "32%");
        }}
      >
        <div className={styles.panelInner}>
          <div className={styles.brandRow}>
            <div className={styles.brandMark}><span /><span /></div>
            <Typography component="span" className={styles.brandWord}>Any2API</Typography>
          </div>
          <Box sx={{ mt: 9 }}>
            <Typography component="h1" sx={{ fontSize: 30, fontWeight: 720, lineHeight: 1.2, color: "#f8fafc" }}>
              进入控制台
            </Typography>
            <Typography sx={{ mt: 1.25, color: "#94a3b8", fontSize: 13 }}>
              使用管理员身份继续
            </Typography>
          </Box>

          <Stack component="form" spacing={2.25} sx={{ mt: 5 }} onSubmit={(event) => { event.preventDefault(); if (ready) login.mutate(); }}>
            {(session.error || challenge.error || login.error) && (
              <Alert severity="error" variant="outlined">
                {login.error?.message ?? session.error?.message ?? "认证挑战暂不可用"}
              </Alert>
            )}
            <DarkField
              label="管理员账号"
              value={username}
              onChange={setUsername}
              icon={<PersonOutlined />}
              autoComplete="username"
            />
            <DarkField
              label="密码"
              value={password}
              onChange={setPassword}
              icon={<LockOutlined />}
              type={showPassword ? "text" : "password"}
              autoComplete="current-password"
              endAdornment={(
                <Tooltip title={showPassword ? "隐藏密码" : "显示密码"}>
                  <IconButton
                    size="small"
                    edge="end"
                    aria-label={showPassword ? "隐藏密码" : "显示密码"}
                    onClick={() => setShowPassword((visible) => !visible)}
                  >
                    {showPassword ? <VisibilityOffOutlined /> : <VisibilityOutlined />}
                  </IconButton>
                </Tooltip>
              )}
            />
            <DarkField
              label={challenge.data ? `${challenge.data.value.expression} =` : "数学验证"}
              value={mathAnswer}
              onChange={setMathAnswer}
              icon={<SecurityOutlined />}
              disabled={!challenge.data}
              inputMode="numeric"
            />
            <div className={styles.powState}>
              {(session.isLoading || challenge.isFetching) ? <CircularProgress size={15} thickness={5} /> : <i />}
              <span>{session.isLoading ? "正在验证现有会话" : challenge.isFetching ? "正在建立计算证明" : challenge.data ? "计算证明已就绪" : "等待认证挑战"}</span>
              {challenge.data && <b>{challenge.data.value.difficulty} 位难度</b>}
            </div>
            <Button
              type="submit"
              variant="contained"
              size="large"
              endIcon={<ArrowForwardOutlined />}
              disabled={!ready || login.isPending || session.isLoading}
              sx={{
                mt: "10px !important",
                height: 46,
                borderRadius: "10px",
                background: "linear-gradient(180deg, #3b82f6 0%, #2563eb 100%)",
                color: "#ffffff",
                fontWeight: 650,
                letterSpacing: "-0.01em",
                boxShadow: "0 1px 2px rgba(37, 99, 235, 0.2), 0 6px 20px rgba(37, 99, 235, 0.25)",
                transition: "all 150ms cubic-bezier(0.4, 0, 0.2, 1)",
                "&:hover": {
                  background: "linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%)",
                  boxShadow: "0 2px 4px rgba(37, 99, 235, 0.3), 0 8px 24px rgba(37, 99, 235, 0.35)",
                  transform: "translateY(-1px)",
                },
                "&:active": {
                  transform: "scale(0.99)",
                },
                "&.Mui-disabled": {
                  background: "rgba(255, 255, 255, 0.06)",
                  color: "rgba(255, 255, 255, 0.3)",
                  border: "1px solid rgba(255, 255, 255, 0.08)",
                  boxShadow: "none",
                },
              }}
            >
              {login.isPending ? "正在验证..." : "安全登录"}
            </Button>
          </Stack>
          <Typography className={styles.footer}>ARM64 · Java · PostgreSQL · Redis</Typography>
        </div>
      </section>
    </main>
  );
}

function DarkField({
  label,
  value,
  onChange,
  icon,
  type = "text",
  disabled = false,
  autoComplete,
  inputMode,
  endAdornment,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  icon: React.ReactNode;
  type?: string;
  disabled?: boolean;
  autoComplete?: string;
  inputMode?: "numeric";
  endAdornment?: React.ReactNode;
}) {
  return (
    <TextField
      fullWidth
      label={label}
      value={value}
      type={type}
      disabled={disabled}
      autoComplete={autoComplete}
      onChange={(event) => onChange(event.target.value)}
      slotProps={{
        htmlInput: { inputMode },
        input: {
          startAdornment: <InputAdornment position="start">{icon}</InputAdornment>,
          endAdornment: endAdornment ? <InputAdornment position="end">{endAdornment}</InputAdornment> : undefined,
        },
      }}
      sx={{
        "& .MuiOutlinedInput-root": {
          height: 52,
          color: "#f8fafc",
          bgcolor: "rgba(255, 255, 255, 0.04)",
          borderRadius: "10px",
          transition: "all 140ms ease",
        },
        "& .MuiOutlinedInput-notchedOutline": { borderColor: "rgba(255, 255, 255, 0.12)" },
        "& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline": { borderColor: "rgba(255, 255, 255, 0.25)" },
        "& .MuiOutlinedInput-root.Mui-focused": {
          boxShadow: "0 0 0 3px rgba(59, 130, 246, 0.25)",
          bgcolor: "rgba(255, 255, 255, 0.06)",
        },
        "& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline": { borderColor: "#3b82f6" },
        "& .MuiInputLabel-root": { color: "#94a3b8" },
        "& .MuiInputLabel-root.Mui-focused": { color: "#3b82f6" },
        "& .MuiInputAdornment-root": { color: "#94a3b8" },
        "& .MuiSvgIcon-root": { fontSize: 19 },
      }}
    />
  );
}
