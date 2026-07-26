"use client";

import {
  AccountTreeOutlined,
  ApiOutlined,
  DashboardOutlined,
  KeyOutlined,
  LanOutlined,
  MenuOutlined,
  ModelTrainingOutlined,
  MonitorHeartOutlined,
  ReceiptLongOutlined,
  SettingsOutlined,
  SmartToyOutlined,
  VpnKeyOutlined
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme
} from "@mui/material";
import { useState, type ReactNode } from "react";

const drawerWidth = 232;
const navigation = [
  ["运行概览", DashboardOutlined],
  ["厂商与模型", ModelTrainingOutlined],
  ["账号池", AccountTreeOutlined],
  ["注册中心", SmartToyOutlined],
  ["生命周期", MonitorHeartOutlined],
  ["任务队列", ReceiptLongOutlined],
  ["代理与打码", LanOutlined],
  ["API 密钥", KeyOutlined],
  ["审计日志", VpnKeyOutlined],
  ["系统设置", SettingsOutlined]
] as const;

export function AppShell({ children }: { children: ReactNode }) {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up("md"));
  const [mobileOpen, setMobileOpen] = useState(false);

  const drawer = (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column", bgcolor: "#172126", color: "#dbe4e7" }}>
      <Box sx={{ px: 2.25, height: 64, display: "flex", alignItems: "center", borderBottom: "1px solid #314047" }}>
        <Box sx={{ width: 30, height: 30, borderRadius: 1, bgcolor: "primary.main", display: "grid", placeItems: "center", mr: 1.25 }}>
          <ApiOutlined sx={{ fontSize: 19, color: "white" }} />
        </Box>
        <Box>
          <Typography sx={{ color: "white", fontWeight: 750, fontSize: 15 }}>Any2API</Typography>
          <Typography sx={{ color: "#91a2a9", fontSize: 10.5 }}>统一模型运行控制台</Typography>
        </Box>
      </Box>
      <List sx={{ px: 1.25, py: 1.5 }}>
        {navigation.map(([label, Icon], index) => (
          <ListItemButton
            key={label}
            selected={index === 0}
            sx={{
              minHeight: 40,
              mb: 0.35,
              borderRadius: 1,
              color: "#bdc9cd",
              "& .MuiListItemIcon-root": { color: "inherit" },
              "&.Mui-selected": { bgcolor: "#223b40", color: "#8ee4df", "&:hover": { bgcolor: "#27464b" } }
            }}
          >
            <ListItemIcon sx={{ minWidth: 34 }}><Icon sx={{ fontSize: 19 }} /></ListItemIcon>
            <ListItemText
              primary={label}
              slotProps={{
                primary: { sx: { fontSize: 13, fontWeight: index === 0 ? 700 : 500 } },
              }}
            />
          </ListItemButton>
        ))}
      </List>
      <Box sx={{ mt: "auto", px: 2.25, py: 2, borderTop: "1px solid #314047" }}>
        <Typography sx={{ color: "#91a2a9", fontSize: 11 }}>Java · Python · PostgreSQL · Redis</Typography>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ minHeight: "100vh", display: "flex" }}>
      <AppBar position="fixed" color="inherit" elevation={0} sx={{ borderBottom: 1, borderColor: "divider", ml: { md: `${drawerWidth}px` }, width: { md: `calc(100% - ${drawerWidth}px)` } }}>
        <Toolbar sx={{ minHeight: "64px !important", px: { xs: 1.5, sm: 2.5 } }}>
          {!desktop && (
            <Tooltip title="打开导航"><IconButton edge="start" onClick={() => setMobileOpen(true)} sx={{ mr: 1 }}><MenuOutlined /></IconButton></Tooltip>
          )}
          <Typography sx={{ fontWeight: 700, fontSize: 14 }}>运行概览</Typography>
          <Box sx={{ flex: 1 }} />
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "success.main" }} />
            <Typography color="text.secondary" sx={{ fontSize: 12 }}>控制面</Typography>
          </Box>
        </Toolbar>
      </AppBar>
      <Box component="nav" sx={{ width: { md: drawerWidth }, flexShrink: { md: 0 } }}>
        <Drawer variant="temporary" open={mobileOpen} onClose={() => setMobileOpen(false)} ModalProps={{ keepMounted: true }} sx={{ display: { xs: "block", md: "none" }, "& .MuiDrawer-paper": { width: drawerWidth } }}>{drawer}</Drawer>
        <Drawer variant="permanent" open sx={{ display: { xs: "none", md: "block" }, "& .MuiDrawer-paper": { width: drawerWidth, border: 0 } }}>{drawer}</Drawer>
      </Box>
      <Box component="main" sx={{ flex: 1, minWidth: 0, pt: 8, bgcolor: "background.default" }}>{children}</Box>
    </Box>
  );
}
