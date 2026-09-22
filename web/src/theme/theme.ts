"use client";

import { createTheme } from "@mui/material/styles";
import { zhCN } from "@mui/material/locale";

// Apple Pro × Linear × Stripe 高级暗黑美学设计系统 Tokens
export const tokens = {
  // 极黑与多层级深色表面体系
  canvas: "#090d16",          // 根底色：深邃星空墨黑 (Obsidian Space Dark)
  canvasSubtle: "#0e1526",    // 次级底色：表头、未激活胶囊、输入框内陷底色
  surface: "#111827",         // 卡片、面板表面：Slate 900 纯粹深邃高质感
  surfaceHover: "#172033",    // 悬浮状态高亮表面
  borderSubtle: "rgba(255, 255, 255, 0.05)",  // 极细微分割线
  border: "rgba(255, 255, 255, 0.09)",        // 标准卡片与面板 1px 晶体边界
  borderStrong: "rgba(255, 255, 255, 0.18)",  // 交互高亮与焦点边界

  // 材质阴影体系（深色高阶扩散与微光边框）
  shadow: {
    sm: "0 1px 2px rgba(0, 0, 0, 0.35)",
    card: "0 1px 3px rgba(0, 0, 0, 0.45), 0 6px 18px -3px rgba(0, 0, 0, 0.35)",
    hover: "0 4px 20px -2px rgba(0, 0, 0, 0.6), 0 0 1px rgba(255, 255, 255, 0.12)",
    modal: "0 24px 64px -12px rgba(0, 0, 0, 0.8), 0 0 0 1px rgba(255, 255, 255, 0.08)",
  },

  // 文字阶梯（保障 WCAG AAA 级高对比可读性）
  text: {
    primary: "#f8fafc",       // Slate 50：纯净透亮主标题与核心数据
    secondary: "#94a3b8",     // Slate 400：辅助说明与元信息标签
    muted: "#64748b",         // Slate 500：时间戳、折叠提示、代码说明
  },

  // 品牌电光蓝 (Electric Azure)
  primary: {
    main: "#3b82f6",          // Blue 500: 在暗色下极具视觉冲击力的电光蓝
    dark: "#2563eb",          // Blue 600
    light: "rgba(59, 130, 246, 0.15)", // 半透明电光蓝呼吸发光层
    gradient: "linear-gradient(180deg, #3b82f6 0%, #1d4ed8 100%)",
    shadow: "0 1px 2px rgba(0, 0, 0, 0.5), 0 0 16px rgba(59, 130, 246, 0.28)",
  },

  // 侧边栏专属暗色阶梯（略低于主画布深度，构建空间进深）
  sidebar: {
    bg: "#060911",            // 侧栏深暗底色
    border: "rgba(255, 255, 255, 0.07)",
    borderSubtle: "rgba(255, 255, 255, 0.05)",
    text: "#f8fafc",
    textMuted: "#94a3b8",
    itemHoverBg: "rgba(255, 255, 255, 0.05)",
    itemHoverText: "#f1f5f9",
    itemActiveBg: "rgba(59, 130, 246, 0.14)",
    itemActiveBorder: "rgba(59, 130, 246, 0.32)",
    itemActiveIcon: "#60a5fa",
    itemIcon: "#64748b",
    footerBg: "rgba(0, 0, 0, 0.25)",
    footerBorder: "rgba(255, 255, 255, 0.05)",
    footerCardBg: "rgba(255, 255, 255, 0.03)",
  },

  // 状态语义色（暗黑微光材质与纯净对比）
  status: {
    emerald: {
      main: "#10b981",
      dark: "#059669",
      light: "rgba(16, 185, 129, 0.12)",
      text: "#34d399",
      border: "rgba(16, 185, 129, 0.25)",
    },
    amber: {
      main: "#f59e0b",
      dark: "#d97706",
      light: "rgba(245, 158, 11, 0.12)",
      text: "#fbbf24",
      border: "rgba(245, 158, 11, 0.25)",
    },
    rose: {
      main: "#f43f5e",
      dark: "#e11d48",
      light: "rgba(244, 63, 94, 0.12)",
      text: "#fb7185",
      border: "rgba(244, 63, 94, 0.25)",
    },
    sky: {
      main: "#0284c7",
      dark: "#0369a1",
      light: "rgba(2, 132, 199, 0.12)",
      text: "#38bdf8",
      border: "rgba(2, 132, 199, 0.25)",
    },
  },
} as const;

export const theme = createTheme({
  palette: {
    mode: "dark",
    primary: {
      main: tokens.primary.main,
      dark: tokens.primary.dark,
      light: tokens.primary.light,
    },
    secondary: {
      main: tokens.text.secondary,
      dark: tokens.text.primary,
      light: tokens.canvasSubtle,
    },
    success: {
      main: tokens.status.emerald.main,
      dark: tokens.status.emerald.dark,
      light: tokens.status.emerald.light,
    },
    warning: {
      main: tokens.status.amber.main,
      dark: tokens.status.amber.dark,
      light: tokens.status.amber.light,
    },
    error: {
      main: tokens.status.rose.main,
      dark: tokens.status.rose.dark,
      light: tokens.status.rose.light,
    },
    info: {
      main: tokens.status.sky.main,
      dark: tokens.status.sky.dark,
      light: tokens.status.sky.light,
    },
    background: {
      default: tokens.canvas,
      paper: tokens.surface,
    },
    text: {
      primary: tokens.text.primary,
      secondary: tokens.text.secondary,
    },
    divider: tokens.borderSubtle,
  },
  shape: {
    borderRadius: 10,
  },
  typography: {
    fontFamily: [
      "-apple-system",
      "BlinkMacSystemFont",
      '"SF Pro Display"',
      '"SF Pro Text"',
      '"Inter"',
      '"Segoe UI"',
      '"PingFang SC"',
      '"Hiragino Sans GB"',
      '"Microsoft YaHei"',
      "sans-serif",
    ].join(","),
    h4: {
      fontSize: "1.5rem",
      fontWeight: 700,
      lineHeight: 1.25,
      letterSpacing: "-0.025em",
      color: tokens.text.primary,
    },
    h5: {
      fontSize: "1.1875rem",
      fontWeight: 650,
      lineHeight: 1.35,
      letterSpacing: "-0.02em",
      color: tokens.text.primary,
    },
    h6: {
      fontSize: "0.9375rem",
      fontWeight: 600,
      lineHeight: 1.4,
      letterSpacing: "-0.015em",
      color: tokens.text.primary,
    },
    subtitle1: {
      fontSize: "0.875rem",
      fontWeight: 600,
      lineHeight: 1.45,
      letterSpacing: "-0.01em",
    },
    subtitle2: {
      fontSize: "0.8125rem",
      fontWeight: 550,
      lineHeight: 1.45,
      letterSpacing: "-0.005em",
    },
    body1: {
      fontSize: "0.875rem",
      lineHeight: 1.55,
      letterSpacing: "-0.005em",
    },
    body2: {
      fontSize: "0.8125rem",
      lineHeight: 1.5,
      letterSpacing: "-0.005em",
    },
    caption: {
      fontSize: "0.75rem",
      fontWeight: 500,
      lineHeight: 1.4,
      letterSpacing: "0.01em",
    },
    button: {
      fontSize: "0.8125rem",
      fontWeight: 600,
      textTransform: "none",
      letterSpacing: "-0.01em",
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "html, body": {
          minWidth: 0,
          minHeight: "100%",
          backgroundColor: tokens.canvas,
          color: tokens.text.primary,
        },
        body: {
          fontFeatureSettings: '"tnum" 1, "cv02" 1, "cv03" 1, "cv04" 1',
          textRendering: "optimizeLegibility",
          WebkitFontSmoothing: "antialiased",
          MozOsxFontSmoothing: "grayscale",
        },
        "*": {
          boxSizing: "border-box",
        },
        "*:focus-visible": {
          outline: `2px solid ${tokens.primary.main}`,
          outlineOffset: 2,
        },
        "@media (prefers-reduced-motion: reduce)": {
          "*, *::before, *::after": {
            animationDuration: "0.01ms !important",
            animationIterationCount: "1 !important",
            transitionDuration: "0.01ms !important",
            scrollBehavior: "auto !important",
          },
        },
        /* 暗黑模式高质感微光滚动条 */
        "::-webkit-scrollbar": {
          width: 6,
          height: 6,
        },
        "::-webkit-scrollbar-track": {
          background: "transparent",
        },
        "::-webkit-scrollbar-thumb": {
          background: "rgba(255, 255, 255, 0.14)",
          borderRadius: 9999,
        },
        "::-webkit-scrollbar-thumb:hover": {
          background: "rgba(255, 255, 255, 0.24)",
        },
      },
    },
    MuiAppBar: {
      defaultProps: {
        color: "inherit",
        elevation: 0,
      },
      styleOverrides: {
        root: {
          backgroundColor: "rgba(9, 13, 22, 0.78)",
          backdropFilter: "blur(20px) saturate(180%)",
          WebkitBackdropFilter: "blur(20px) saturate(180%)",
          borderBottom: `1px solid ${tokens.borderSubtle}`,
          transition: "background-color 200ms ease, border-color 200ms ease",
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
        },
        outlined: {
          borderColor: tokens.border,
          borderRadius: 14,
          backgroundColor: tokens.surface,
          boxShadow: "0 1px 2px rgba(0, 0, 0, 0.3), 0 4px 16px -2px rgba(0, 0, 0, 0.25)",
        },
      },
    },
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          minHeight: 36,
          borderRadius: 8,
          paddingInline: 14,
          fontWeight: 600,
          transition: "all 140ms cubic-bezier(0.4, 0, 0.2, 1)",
          "&:active": {
            transform: "scale(0.98)",
          },
        },
        contained: {
          background: tokens.primary.gradient,
          boxShadow: tokens.primary.shadow,
          border: "1px solid rgba(255, 255, 255, 0.15)",
          "&:hover": {
            background: "linear-gradient(180deg, #2563eb 0%, #1d4ed8 100%)",
            boxShadow: "0 2px 4px rgba(37, 99, 235, 0.3), 0 0 20px rgba(59, 130, 246, 0.35)",
          },
        },
        outlined: {
          borderColor: tokens.border,
          color: tokens.text.primary,
          backgroundColor: "rgba(255, 255, 255, 0.03)",
          boxShadow: "0 1px 2px rgba(0, 0, 0, 0.2)",
          "&:hover": {
            borderColor: tokens.borderStrong,
            backgroundColor: "rgba(255, 255, 255, 0.07)",
          },
        },
        startIcon: {
          marginRight: 6,
          "& > *:first-of-type": { fontSize: 17 },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          width: 34,
          height: 34,
          borderRadius: 8,
          color: tokens.text.secondary,
          transition: "all 140ms ease",
          "&:hover": {
            backgroundColor: "rgba(255, 255, 255, 0.08)",
            color: tokens.text.primary,
          },
          "&:active": {
            transform: "scale(0.95)",
          },
        },
      },
    },
    MuiTextField: {
      defaultProps: { size: "small" },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          minHeight: 38,
          borderRadius: 8,
          backgroundColor: tokens.canvasSubtle,
          transition: "border-color 140ms ease, box-shadow 140ms ease",
          "& .MuiOutlinedInput-notchedOutline": {
            borderColor: tokens.border,
            borderWidth: "1px",
          },
          "&:hover .MuiOutlinedInput-notchedOutline": {
            borderColor: tokens.borderStrong,
          },
          "&.Mui-focused": {
            boxShadow: "0 0 0 3px rgba(59, 130, 246, 0.2)",
            "& .MuiOutlinedInput-notchedOutline": {
              borderColor: tokens.primary.main,
              borderWidth: "1.5px",
            },
          },
        },
        input: {
          paddingTop: 8.5,
          paddingBottom: 8.5,
          fontSize: 13,
          color: tokens.text.primary,
        },
      },
    },
    MuiInputLabel: {
      styleOverrides: {
        root: {
          fontSize: 13,
          color: tokens.text.secondary,
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          height: 24,
          borderRadius: 9999, // 苹果/Stripe 胶囊 Pill 设计
          fontSize: 11.5,
          fontWeight: 600,
          letterSpacing: "0.01em",
          borderWidth: 1,
        },
        outlined: {
          borderColor: tokens.border,
          backgroundColor: "rgba(255, 255, 255, 0.04)",
        },
        colorSuccess: {
          backgroundColor: tokens.status.emerald.light,
          color: tokens.status.emerald.text,
          borderColor: tokens.status.emerald.border,
        },
        colorWarning: {
          backgroundColor: tokens.status.amber.light,
          color: tokens.status.amber.text,
          borderColor: tokens.status.amber.border,
        },
        colorError: {
          backgroundColor: tokens.status.rose.light,
          color: tokens.status.rose.text,
          borderColor: tokens.status.rose.border,
        },
        colorInfo: {
          backgroundColor: tokens.status.sky.light,
          color: tokens.status.sky.text,
          borderColor: tokens.status.sky.border,
        },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true, enterDelay: 300 },
      styleOverrides: {
        tooltip: {
          backgroundColor: "#1e293b",
          color: "#f8fafc",
          fontSize: 11.5,
          fontWeight: 500,
          borderRadius: 6,
          padding: "5px 9px",
          border: "1px solid rgba(255, 255, 255, 0.12)",
          boxShadow: "0 4px 14px rgba(0, 0, 0, 0.4)",
        },
        arrow: {
          color: "#1e293b",
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 16,
          backgroundColor: tokens.surface,
          border: `1px solid ${tokens.border}`,
          boxShadow: "0 24px 64px -12px rgba(0, 0, 0, 0.7), 0 0 0 1px rgba(255, 255, 255, 0.08)",
          overflow: "hidden",
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: {
        root: {
          padding: "20px 24px",
          fontSize: 16.5,
          fontWeight: 700,
          borderBottom: `1px solid ${tokens.borderSubtle}`,
        },
      },
    },
    MuiDialogActions: {
      styleOverrides: {
        root: {
          minHeight: 64,
          padding: "14px 24px",
          borderTop: `1px solid ${tokens.borderSubtle}`,
          backgroundColor: tokens.canvas,
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          height: 46,
          borderBottom: `1px solid ${tokens.borderSubtle}`,
          borderRight: "0 !important", // 清除多余垂直九宫格线
          padding: "9px 16px",
          fontSize: "0.8125rem",
          color: tokens.text.primary,
        },
        head: {
          height: 40,
          color: tokens.text.secondary,
          fontSize: "0.71875rem",
          fontWeight: 650,
          letterSpacing: "0.03em",
          textTransform: "uppercase",
          backgroundColor: "rgba(14, 21, 38, 0.9)",
          backdropFilter: "blur(8px)",
          whiteSpace: "nowrap",
          borderBottom: `1px solid ${tokens.border}`,
        },
      },
    },
    MuiTableContainer: {
      styleOverrides: {
        root: {
          scrollbarColor: "rgba(255, 255, 255, 0.14) transparent",
          scrollbarWidth: "thin",
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          transition: "background-color 100ms ease",
          "&.MuiTableRow-hover:hover": {
            backgroundColor: "rgba(255, 255, 255, 0.035) !important",
          },
        },
      },
    },
    MuiTablePagination: {
      styleOverrides: {
        toolbar: {
          minHeight: 48,
          paddingInline: 16,
          borderTop: `1px solid ${tokens.borderSubtle}`,
        },
        selectLabel: { fontSize: 12, color: tokens.text.secondary },
        displayedRows: { fontSize: 12, color: tokens.text.secondary },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 42 },
        indicator: {
          height: 2.5,
          borderRadius: "3px 3px 0 0",
          backgroundColor: tokens.primary.main,
        },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 42,
          padding: "8px 16px",
          fontSize: 13,
          fontWeight: 600,
          color: tokens.text.secondary,
          transition: "color 140ms ease",
          "&.Mui-selected": {
            color: tokens.primary.main,
          },
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: {
          borderRadius: 10,
          alignItems: "center",
          border: "1px solid",
          fontSize: 13,
          boxShadow: "0 1px 3px rgba(0, 0, 0, 0.2)",
          "&.MuiAlert-colorSuccess": {
            backgroundColor: tokens.status.emerald.light,
            color: tokens.status.emerald.text,
            borderColor: tokens.status.emerald.border,
          },
          "&.MuiAlert-colorWarning": {
            backgroundColor: tokens.status.amber.light,
            color: tokens.status.amber.text,
            borderColor: tokens.status.amber.border,
          },
          "&.MuiAlert-colorError": {
            backgroundColor: tokens.status.rose.light,
            color: tokens.status.rose.text,
            borderColor: tokens.status.rose.border,
          },
          "&.MuiAlert-colorInfo": {
            backgroundColor: tokens.status.sky.light,
            color: tokens.status.sky.text,
            borderColor: tokens.status.sky.border,
          },
        },
        message: { paddingBlock: 2 },
      },
    },
    MuiSwitch: {
      styleOverrides: {
        root: {
          width: 36,
          height: 20,
          padding: 0,
          display: "flex",
          "&:active": {
            "& .MuiSwitch-thumb": {
              width: 17,
            },
            "& .MuiSwitch-switchBase.Mui-checked": {
              transform: "translateX(15px)",
            },
          },
        },
        switchBase: {
          padding: 2,
          "&.Mui-checked": {
            transform: "translateX(16px)",
            color: "#fff",
            "& + .MuiSwitch-track": {
              opacity: 1,
              backgroundColor: tokens.primary.main,
            },
          },
        },
        thumb: {
          boxShadow: "0 2px 4px 0 rgba(0, 0, 0, 0.3)",
          width: 16,
          height: 16,
          borderRadius: 8,
          transition: "width 120ms ease, transform 120ms ease",
        },
        track: {
          borderRadius: 20 / 2,
          opacity: 1,
          backgroundColor: "rgba(255, 255, 255, 0.18)",
          boxSizing: "border-box",
          transition: "background-color 140ms ease",
        },
      },
    },
    MuiLinearProgress: {
      styleOverrides: {
        root: {
          height: 3,
          borderRadius: 2,
          backgroundColor: "rgba(255, 255, 255, 0.08)",
        },
      },
    },
  },
}, zhCN);
