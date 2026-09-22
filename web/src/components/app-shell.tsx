"use client";

import {
  AccountTreeOutlined,
  ApiOutlined,
  AccountCircleOutlined,
  AutoAwesomeOutlined,
  DashboardOutlined,
  AutorenewOutlined,
  LogoutOutlined,
  LanOutlined,
  KeyOutlined,
  ReceiptLongOutlined,
  HistoryOutlined,
  MenuOutlined,
  SettingsOutlined,
  RuleOutlined,
  TuneOutlined,
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  CircularProgress,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { type ReactNode, useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { tokens } from "@/theme/theme";

const shellMetrics = {
  drawerWidth: 244,
  headerHeight: 56,
} as const;

const navigation = [
  ["运行概览", DashboardOutlined, "/"],
  ["模型操练台", AutoAwesomeOutlined, "/playground"],
  ["账号池", AccountTreeOutlined, "/accounts"],
  ["模型策略", TuneOutlined, "/models"],
  ["运行时规则", RuleOutlined, "/runtime-rules"],
  ["生命周期", AutorenewOutlined, "/lifecycle"],
  ["请求记录", ReceiptLongOutlined, "/requests"],
  ["操作记录", HistoryOutlined, "/operations"],
  ["代理池", LanOutlined, "/proxy-pools"],
  ["分发密钥", KeyOutlined, "/api-keys"],
  ["系统设置", SettingsOutlined, "/settings"],
] as const;

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down("md"));
  const [mobileOpen, setMobileOpen] = useState(false);
  const session = useQuery({ queryKey: ["admin-session"], queryFn: api.session, retry: false });
  const logout = useMutation({
    mutationFn: api.logout,
    onSettled: () => {
      queryClient.clear();
      router.replace("/login");
    },
  });
  const activeNavigation = navigation.find(([, , href]) => (
    href === "/" ? pathname === href : pathname === href || pathname.startsWith(`${href}/`)
  ));
  const appBarTitle = activeNavigation?.[2] === "/lifecycle" ? "注册与生命周期" : activeNavigation?.[0] ?? "Any2API";

  useEffect(() => {
    if (session.isError || session.data?.authenticated === false) router.replace("/login");
  }, [router, session.data?.authenticated, session.isError]);

  if (session.isLoading || session.isError || !session.data?.authenticated) return <SessionGate />;

  const drawer = (
    <Box
      sx={{
        height: "100%",
        display: "flex",
        flexDirection: "column",
        bgcolor: tokens.sidebar.bg,
        color: tokens.sidebar.text,
        borderRight: `1px solid ${tokens.sidebar.border}`,
      }}
    >
      {/* 品牌 Brand Header */}
      <Box
        sx={{
          px: 2.25,
          height: shellMetrics.headerHeight,
          display: "flex",
          alignItems: "center",
          borderBottom: `1px solid ${tokens.sidebar.borderSubtle}`,
        }}
      >
        <Box
          sx={{
            width: 32,
            height: 32,
            borderRadius: "9px",
            background: "linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)",
            display: "grid",
            placeItems: "center",
            mr: 1.5,
            boxShadow: "0 2px 8px rgba(37, 99, 235, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.25)",
          }}
        >
          <ApiOutlined sx={{ fontSize: 18, color: "#ffffff" }} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography
              noWrap
              sx={{
                color: "#ffffff",
                fontWeight: 700,
                fontSize: 14.5,
                letterSpacing: "-0.02em",
              }}
            >
              Any2API
            </Typography>
            <Box
              sx={{
                px: 0.75,
                py: 0.15,
                borderRadius: "4px",
                bgcolor: "rgba(255, 255, 255, 0.08)",
                fontSize: 10,
                fontWeight: 600,
                color: tokens.sidebar.textMuted,
                letterSpacing: "0.02em",
              }}
            >
              PRO
            </Box>
          </Stack>
          <Typography
            noWrap
            sx={{
              color: tokens.sidebar.itemIcon,
              fontSize: 11,
              fontWeight: 500,
              letterSpacing: "-0.01em",
            }}
          >
            模型运营与网关中心
          </Typography>
        </Box>
      </Box>

      {/* 导航菜单 Navigation List */}
      <List aria-label="主导航" sx={{ px: 1.25, py: 2, flex: 1, overflowY: "auto" }}>
        {navigation.map(([label, Icon, href]) => {
          const selected = activeNavigation?.[2] === href;
          return (
            <ListItemButton
              key={label}
              component={Link}
              href={href}
              onClick={() => setMobileOpen(false)}
              selected={selected}
              sx={{
                minHeight: 38,
                mb: 0.5,
                px: 1.5,
                borderRadius: "8px",
                color: selected ? tokens.sidebar.text : tokens.sidebar.textMuted,
                bgcolor: selected ? tokens.sidebar.itemActiveBg : "transparent",
                border: selected ? `1px solid ${tokens.sidebar.itemActiveBorder}` : "1px solid transparent",
                transition: "all 140ms cubic-bezier(0.4, 0, 0.2, 1)",
                "& .MuiListItemIcon-root": {
                  color: selected ? tokens.sidebar.itemActiveIcon : tokens.sidebar.itemIcon,
                  transition: "color 140ms ease",
                },
                "&:hover": {
                  bgcolor: selected ? tokens.sidebar.itemActiveBg : tokens.sidebar.itemHoverBg,
                  color: selected ? tokens.sidebar.text : tokens.sidebar.itemHoverText,
                  "& .MuiListItemIcon-root": {
                    color: selected ? tokens.sidebar.itemActiveIcon : tokens.sidebar.itemHoverText,
                  },
                },
              }}
            >
              <ListItemIcon sx={{ minWidth: 30 }}>
                <Icon sx={{ fontSize: 18 }} />
              </ListItemIcon>
              <ListItemText
                primary={label}
                slotProps={{
                  primary: {
                    sx: {
                      fontSize: 13,
                      fontWeight: selected ? 650 : 500,
                      letterSpacing: "-0.01em",
                    },
                  },
                }}
              />
            </ListItemButton>
          );
        })}
      </List>

      {/* 底部状态 Footer Status */}
      <Box
        sx={{
          mt: "auto",
          p: 1.75,
          borderTop: `1px solid ${tokens.sidebar.borderSubtle}`,
          bgcolor: tokens.sidebar.footerBg,
        }}
      >
        <Stack
          direction="row"
          spacing={1.25}
          sx={{
            alignItems: "center",
            px: 1.25,
            py: 1,
            borderRadius: "8px",
            bgcolor: tokens.sidebar.footerCardBg,
            border: `1px solid ${tokens.sidebar.footerBorder}`,
          }}
        >
          <Box
            sx={{
              width: 8,
              height: 8,
              borderRadius: "50%",
              bgcolor: tokens.status.emerald.main,
              boxShadow: "0 0 0 3px rgba(16, 185, 129, 0.2)",
            }}
          />
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography
              noWrap
              sx={{ color: tokens.sidebar.itemHoverText, fontSize: 12, fontWeight: 600, letterSpacing: "-0.01em" }}
            >
              控制集群就绪
            </Typography>
            <Typography noWrap sx={{ color: tokens.sidebar.itemIcon, fontSize: 10.5 }}>
              Any2API 正常服务中
            </Typography>
          </Box>
        </Stack>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", bgcolor: "background.default" }}>
      {/* 顶部 AppBar (Apple Frosted Glass) */}
      <AppBar
        position="fixed"
        sx={{
          zIndex: (value) => value.zIndex.drawer + 1,
          borderBottom: "1px solid",
          borderColor: "divider",
          bgcolor: "rgba(9, 13, 22, 0.82)",
          backdropFilter: "blur(20px) saturate(180%)",
          WebkitBackdropFilter: "blur(20px) saturate(180%)",
          color: "text.primary",
          ml: compact ? 0 : `${shellMetrics.drawerWidth}px`,
          width: compact ? "100%" : `calc(100% - ${shellMetrics.drawerWidth}px)`,
          boxShadow: "0 1px 0 rgba(255, 255, 255, 0.05)",
        }}
      >
        <Toolbar sx={{ minHeight: `${shellMetrics.headerHeight}px !important`, px: { xs: 2, sm: 3 } }}>
          {compact ? (
            <Tooltip title="打开导航">
              <IconButton
                aria-label="打开导航"
                onClick={() => setMobileOpen(true)}
                sx={{ mr: 1.5, color: "text.primary" }}
              >
                <MenuOutlined sx={{ fontSize: 20 }} />
              </IconButton>
            </Tooltip>
          ) : null}

          {/* 页面标题 & 面包屑指示 */}
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography
              sx={{
                fontWeight: 700,
                fontSize: 15,
                letterSpacing: "-0.02em",
                color: "text.primary",
              }}
            >
              {appBarTitle}
            </Typography>
          </Stack>

          <Box sx={{ flex: 1 }} />

          {/* 右侧管理员信息与状态胶囊 */}
          <Stack direction="row" spacing={1.5} sx={{ alignItems: "center" }}>
            <Box
              sx={{
                display: { xs: "none", sm: "flex" },
                alignItems: "center",
                gap: 1,
                px: 1.25,
                py: 0.5,
                borderRadius: "9999px",
                bgcolor: tokens.status.emerald.light,
                border: `1px solid ${tokens.status.emerald.border}`,
              }}
            >
              <Box
                sx={{
                  width: 6,
                  height: 6,
                  borderRadius: "50%",
                  bgcolor: tokens.status.emerald.main,
                  boxShadow: "0 0 0 2px rgba(16, 185, 129, 0.25)",
                }}
              />
              <Typography sx={{ color: tokens.status.emerald.text, fontSize: 11.5, fontWeight: 600 }}>
                集群健康
              </Typography>
            </Box>

            <Box
              sx={{
                display: { xs: "none", sm: "flex" },
                alignItems: "center",
                gap: 1,
                pl: 1,
                borderLeft: "1px solid",
                borderColor: "divider",
              }}
            >
              <AccountCircleOutlined sx={{ fontSize: 20, color: "text.secondary" }} />
              <Typography sx={{ fontSize: 13, fontWeight: 600, color: "text.primary" }}>
                {session.data?.username ?? "管理员"}
              </Typography>
            </Box>

            <Tooltip title="安全退出">
              <IconButton
                aria-label="退出登录"
                onClick={() => logout.mutate()}
                disabled={logout.isPending}
                sx={{
                  color: "text.secondary",
                  border: "1px solid",
                  borderColor: tokens.border,
                  bgcolor: "background.paper",
                }}
              >
                <LogoutOutlined sx={{ fontSize: 17 }} />
              </IconButton>
            </Tooltip>
          </Stack>
        </Toolbar>
      </AppBar>

      {/* 侧边抽屉 / 常驻侧边栏 */}
      <Box component="nav" aria-label="主导航">
        {compact ? (
          <Drawer
            variant="temporary"
            open={mobileOpen}
            onClose={() => setMobileOpen(false)}
            ModalProps={{ keepMounted: true }}
            sx={{
              "& .MuiDrawer-paper": {
                width: shellMetrics.drawerWidth,
                border: 0,
                boxShadow: "0 20px 40px rgba(0, 0, 0, 0.35)",
              },
            }}
          >
            {drawer}
          </Drawer>
        ) : (
          <Drawer
            variant="permanent"
            open
            sx={{
              width: shellMetrics.drawerWidth,
              flexShrink: 0,
              "& .MuiDrawer-paper": {
                width: shellMetrics.drawerWidth,
                border: 0,
              },
            }}
          >
            {drawer}
          </Drawer>
        )}
      </Box>

      {/* 主视图内容区域 */}
      <Box
        component="main"
        sx={{
          flex: 1,
          minWidth: 0,
          minHeight: "100vh",
          pt: `${shellMetrics.headerHeight}px`,
          bgcolor: "background.default",
        }}
      >
        {children}
      </Box>
    </Box>
  );
}

function SessionGate() {
  return (
    <Box
      sx={{
        minWidth: 0,
        minHeight: "100vh",
        display: "grid",
        placeItems: "center",
        bgcolor: tokens.sidebar.bg,
      }}
    >
      <Stack spacing={2.5} sx={{ alignItems: "center" }}>
        <Box
          sx={{
            width: 44,
            height: 44,
            borderRadius: "12px",
            background: "linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)",
            display: "grid",
            placeItems: "center",
            boxShadow: "0 4px 16px rgba(37, 99, 235, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.3)",
          }}
        >
          <ApiOutlined sx={{ color: "#ffffff", fontSize: 24 }} />
        </Box>
        <CircularProgress size={22} thickness={4} sx={{ color: tokens.primary.main }} />
        <Typography
          sx={{
            color: tokens.sidebar.textMuted,
            fontFamily: "ui-monospace, monospace",
            fontSize: 11.5,
            letterSpacing: "0.02em",
          }}
        >
          验证管理员权限与工作态...
        </Typography>
      </Stack>
    </Box>
  );
}
