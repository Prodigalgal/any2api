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
  MenuOutlined,
  SettingsOutlined,
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

const drawerWidth = 224;
const navigation = [
  ["运行概览", DashboardOutlined, "/"],
  ["账号池", AccountTreeOutlined, "/accounts"],
  ["模型策略", TuneOutlined, "/models"],
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

  useEffect(() => {
    if (session.isError || session.data?.authenticated === false) router.replace("/login");
  }, [router, session.data?.authenticated, session.isError]);

  if (session.isLoading || session.isError || !session.data?.authenticated) return <SessionGate />;

  const drawer = (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column", bgcolor: "#121b20", color: "#d8e2e5" }}>
      <Box sx={{ px: 2, height: 64, display: "flex", alignItems: "center", borderBottom: "1px solid #2b373d" }}>
        <Box sx={{ width: 30, height: 30, borderRadius: 1, bgcolor: "primary.main", display: "grid", placeItems: "center", mr: 1.25 }}>
          <ApiOutlined sx={{ fontSize: 19, color: "white" }} />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography noWrap sx={{ color: "white", fontWeight: 760, fontSize: 15 }}>Any2API</Typography>
          <Typography noWrap sx={{ color: "#92a2a9", fontSize: 10.5 }}>MODEL OPERATIONS</Typography>
        </Box>
      </Box>
      <List aria-label="主导航" sx={{ px: 1.25, py: 1.5 }}>
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
                minHeight: 40,
                mb: 0.5,
                borderRadius: 1,
                color: "#bbc8cc",
                position: "relative",
                "& .MuiListItemIcon-root": { color: "inherit" },
                "&.Mui-selected": {
                  bgcolor: "#20353a",
                  color: "#8ce0da",
                  "&:before": {
                    content: '\"\"', position: "absolute", left: 0, top: 9, bottom: 9,
                    width: 3, borderRadius: "0 2px 2px 0", bgcolor: "#55cbc4",
                  },
                  "&:hover": { bgcolor: "#254047" },
                },
              }}
            >
              <ListItemIcon sx={{ minWidth: 34 }}><Icon sx={{ fontSize: 19 }} /></ListItemIcon>
              <ListItemText primary={label} slotProps={{ primary: { sx: { fontSize: 13, fontWeight: selected ? 700 : 520 } } }} />
            </ListItemButton>
          );
        })}
      </List>
      <Box sx={{ mt: "auto", px: 2, py: 1.75, borderTop: "1px solid #2b373d" }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "#47bf87" }} />
          <Typography sx={{ color: "#9aabb1", fontSize: 10.5 }}>控制面在线</Typography>
        </Stack>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ minHeight: "100vh", display: "flex" }}>
      <AppBar
        position="fixed"
        sx={{
          zIndex: (value) => value.zIndex.drawer + 1,
          borderBottom: 1,
          borderColor: "divider",
          ml: compact ? 0 : `${drawerWidth}px`,
          width: compact ? "100%" : `calc(100% - ${drawerWidth}px)`,
        }}
      >
        <Toolbar sx={{ minHeight: "56px !important", px: { xs: 1.5, sm: 2.5 } }}>
          {compact ? (
            <Tooltip title="打开导航">
              <IconButton aria-label="打开导航" onClick={() => setMobileOpen(true)} sx={{ mr: 1 }}>
                <MenuOutlined />
              </IconButton>
            </Tooltip>
          ) : null}
          <Typography noWrap sx={{ fontWeight: 720, fontSize: 14 }}>
            {activeNavigation?.[0] ?? "Any2API"}
          </Typography>
          <Box sx={{ flex: 1 }} />
          <Stack direction="row" spacing={1} sx={{ alignItems: "center", minWidth: 0 }}>
            <Box sx={{ display: { xs: "none", sm: "flex" }, alignItems: "center", gap: 0.75 }}>
              <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "success.main" }} />
              <Typography color="text.secondary" sx={{ fontSize: 11.5, whiteSpace: "nowrap" }}>正常运行</Typography>
              <Box sx={{ width: 1, height: 18, bgcolor: "divider", mx: 0.5 }} />
            </Box>
            <AccountCircleOutlined sx={{ display: { xs: "none", sm: "block" }, fontSize: 18, color: "text.secondary" }} />
            <Typography noWrap sx={{ display: { xs: "none", sm: "block" }, maxWidth: 140, fontSize: 12, fontWeight: 650 }}>
              {session.data?.username ?? "admin"}
            </Typography>
            <Tooltip title="退出登录">
              <span>
                <IconButton aria-label="退出登录" onClick={() => logout.mutate()} disabled={logout.isPending}>
                  <LogoutOutlined sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        </Toolbar>
      </AppBar>
      <Box component="nav" aria-label="主导航">
        {compact ? (
          <Drawer
            variant="temporary"
            open={mobileOpen}
            onClose={() => setMobileOpen(false)}
            ModalProps={{ keepMounted: true }}
            sx={{ "& .MuiDrawer-paper": { width: drawerWidth, border: 0 } }}
          >
            {drawer}
          </Drawer>
        ) : (
          <Drawer
            variant="permanent"
            open
            sx={{ width: drawerWidth, flexShrink: 0, "& .MuiDrawer-paper": { width: drawerWidth, border: 0 } }}
          >
            {drawer}
          </Drawer>
        )}
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 0, pt: "56px", bgcolor: "background.default" }}>
        {children}
      </Box>
    </Box>
  );
}

function SessionGate() {
  return (
    <Box sx={{ minWidth: 0, minHeight: "100vh", display: "grid", placeItems: "center", bgcolor: "#10171b" }}>
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
