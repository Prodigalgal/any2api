"use client";

import {
  AccountTreeOutlined,
  ApiOutlined,
  AccountCircleOutlined,
  DashboardOutlined,
  AutorenewOutlined,
  LogoutOutlined,
  LanOutlined,
  KeyOutlined,
  ReceiptLongOutlined,
  HistoryOutlined,
  SettingsOutlined,
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
} from "@mui/material";
import { type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api } from "@/lib/api";

const drawerWidth = 240;
const navigation = [
  ["运行概览", DashboardOutlined, "/"],
  ["账号池", AccountTreeOutlined, "/accounts"],
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

  useEffect(() => {
    if (session.isError || session.data?.authenticated === false) router.replace("/login");
  }, [router, session.data?.authenticated, session.isError]);

  if (session.isLoading || session.isError || !session.data?.authenticated) return <SessionGate />;

  const drawer = (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column", bgcolor: "#121c20", color: "#dbe4e7" }}>
      <Box sx={{ px: 2.5, height: 64, display: "flex", alignItems: "center", borderBottom: "1px solid #2c3a40" }}>
        <Box sx={{ width: 30, height: 30, borderRadius: 1, bgcolor: "primary.main", display: "grid", placeItems: "center", mr: 1.25 }}>
          <ApiOutlined sx={{ fontSize: 19, color: "white" }} />
        </Box>
        <Box>
          <Typography sx={{ color: "white", fontWeight: 750, fontSize: 15 }}>Any2API</Typography>
          <Typography sx={{ color: "#91a2a9", fontSize: 10.5 }}>统一模型运行控制台</Typography>
        </Box>
      </Box>
      <List sx={{ px: 1.25, py: 1.5 }}>
        {navigation.map(([label, Icon, href]) => (
          <ListItemButton
            key={label}
            component={Link}
            href={href}
            selected={activeNavigation?.[2] === href}
            sx={{
              minHeight: 42,
              mb: 0.5,
              borderRadius: 1,
              color: "#bdc9cd",
              position: "relative",
              "& .MuiListItemIcon-root": { color: "inherit" },
              "&.Mui-selected": {
                bgcolor: "#20363a",
                color: "#8ee4df",
                "&:before": {
                  content: '""', position: "absolute", left: 0, top: 9, bottom: 9,
                  width: 3, borderRadius: "0 2px 2px 0", bgcolor: "#62d8d0",
                },
                "&:hover": { bgcolor: "#254147" },
              },
            }}
          >
            <ListItemIcon sx={{ minWidth: 34 }}><Icon sx={{ fontSize: 19 }} /></ListItemIcon>
            <ListItemText
              primary={label}
              slotProps={{
                primary: { sx: { fontSize: 13, fontWeight: activeNavigation?.[2] === href ? 700 : 500 } },
              }}
            />
          </ListItemButton>
        ))}
      </List>
      <Box sx={{ mt: "auto", px: 2.5, py: 2, borderTop: "1px solid #2c3a40" }}>
        <Typography sx={{ color: "#91a2a9", fontSize: 11 }}>Java · Python · PostgreSQL · Redis</Typography>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ minHeight: "100vh", display: "flex" }}>
      <AppBar position="fixed" color="inherit" elevation={0} sx={{ borderBottom: 1, borderColor: "divider", ml: `${drawerWidth}px`, width: `calc(100% - ${drawerWidth}px)` }}>
        <Toolbar sx={{ minHeight: "64px !important", px: 3 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 14 }}>
            {activeNavigation?.[0] ?? "Any2API"}
          </Typography>
          <Box sx={{ flex: 1 }} />
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}>
            <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "success.main" }} />
            <Typography color="text.secondary" sx={{ fontSize: 12, whiteSpace: "nowrap" }}>控制面在线</Typography>
            <Box sx={{ width: "1px", height: 18, flexShrink: 0, bgcolor: "divider", mx: 0.5 }} />
            <AccountCircleOutlined sx={{ fontSize: 18, color: "text.secondary" }} />
            <Typography sx={{ fontSize: 12, fontWeight: 650, whiteSpace: "nowrap" }}>{session.data?.username ?? "admin"}</Typography>
            <Tooltip title="退出登录">
              <IconButton size="small" onClick={() => logout.mutate()} disabled={logout.isPending}>
                <LogoutOutlined sx={{ fontSize: 18 }} />
              </IconButton>
            </Tooltip>
          </Box>
        </Toolbar>
      </AppBar>
      <Box component="nav" sx={{ width: drawerWidth, flexShrink: 0 }}>
        <Drawer variant="permanent" open sx={{ "& .MuiDrawer-paper": { width: drawerWidth, border: 0 } }}>{drawer}</Drawer>
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 1040, pt: 8, bgcolor: "background.default" }}>{children}</Box>
    </Box>
  );
}

function SessionGate() {
  return (
    <Box sx={{ minWidth: 1120, minHeight: "100vh", display: "grid", placeItems: "center", bgcolor: "#071014" }}>
      <Stack spacing={2} sx={{ alignItems: "center" }}>
        <Box sx={{ width: 38, height: 38, border: "1px solid #4bbfb9", transform: "rotate(45deg)", display: "grid", placeItems: "center" }}>
          <ApiOutlined sx={{ color: "#7be0da", fontSize: 21, transform: "rotate(-45deg)" }} />
        </Box>
        <CircularProgress size={20} thickness={4} sx={{ color: "#62d8d0" }} />
        <Typography sx={{ color: "#839399", fontFamily: "ui-monospace, monospace", fontSize: 10 }}>
          VERIFYING ADMIN SESSION
        </Typography>
      </Stack>
    </Box>
  );
}
