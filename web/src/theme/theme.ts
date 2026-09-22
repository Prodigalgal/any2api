"use client";

import { createTheme } from "@mui/material/styles";
import { zhCN } from "@mui/material/locale";

// Stripe × Apple 精准调色盘与材质设计系统 Tokens
export const tokens = {
  canvas: "#f8fafc",      // Slate 50 纯净通透底色
  canvasSubtle: "#f1f5f9",// Slate 100
  surface: "#ffffff",
  surfaceHover: "#f8fafc",
  borderSubtle: "rgba(15, 23, 42, 0.06)",
  border: "rgba(15, 23, 42, 0.09)",
  borderStrong: "rgba(15, 23, 42, 0.16)",

  // 文字阶梯
  text: {
    primary: "#0f172a",   // Slate 900
    secondary: "#475569", // Slate 600
    muted: "#94a3b8",     // Slate 400
  },

  // 品牌主色 (Apple / Stripe 电光深蓝)
  primary: {
    main: "#2563eb",
    dark: "#1d4ed8",
    light: "#eff6ff",
    gradient: "linear-gradient(180deg, #3b82f6 0%, #2563eb 100%)",
    shadow: "0 1px 2px rgba(37, 99, 235, 0.2), 0 4px 12px rgba(37, 99, 235, 0.18)",
  },

  // 侧边栏专属设计 Tokens
  sidebar: {
    bg: "#0b0f19",
    border: "rgba(255, 255, 255, 0.07)",
    borderSubtle: "rgba(255, 255, 255, 0.06)",
    text: "#f8fafc",
    textMuted: "#94a3b8",
    itemHoverBg: "rgba(255, 255, 255, 0.05)",
    itemHoverText: "#e2e8f0",
    itemActiveBg: "rgba(37, 99, 235, 0.18)",
    itemActiveBorder: "rgba(59, 130, 246, 0.28)",
    itemActiveIcon: "#60a5fa",
    itemIcon: "#64748b",
    footerBg: "rgba(0, 0, 0, 0.15)",
    footerBorder: "rgba(255, 255, 255, 0.05)",
    footerCardBg: "rgba(255, 255, 255, 0.03)",
  },

  // 状态语义色
  status: {
    emerald: {
      main: "#10b981",
      dark: "#059669",
      light: "#ecfdf5",
      text: "#065f46",
      border: "#a7f3d0",
    },
    amber: {
      main: "#f59e0b",
      dark: "#d97706",
      light: "#fffbeb",
      text: "#92400e",
      border: "#fde68a",
    },
    rose: {
      main: "#f43f5e",
      dark: "#e11d48",
      light: "#fff1f2",
      text: "#9f1239",
      border: "#fecdd3",
    },
    sky: {
      main: "#0284c7",
      dark: "#0369a1",
      light: "#f0f9ff",
      text: "#075985",
      border: "#bae6fd",
    },
  },
} as const;

export const theme = createTheme({
  palette: {
    mode: "light",
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
        /* 精细极客滚动条 */
        "::-webkit-scrollbar": {
          width: 6,
          height: 6,
        },
        "::-webkit-scrollbar-track": {
          background: "transparent",
        },
        "::-webkit-scrollbar-thumb": {
          background: "rgba(15, 23, 42, 0.15)",
          borderRadius: 9999,
        },
        "::-webkit-scrollbar-thumb:hover": {
          background: "rgba(15, 23, 42, 0.28)",
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
          backgroundColor: "rgba(255, 255, 255, 0.82)",
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
          boxShadow: "0 1px 2px rgba(15, 23, 42, 0.04), 0 4px 16px -2px rgba(15, 23, 42, 0.02)",
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
            boxShadow: "0 2px 4px rgba(37, 99, 235, 0.25), 0 6px 20px rgba(37, 99, 235, 0.2)",
          },
        },
        outlined: {
          borderColor: tokens.border,
          color: tokens.text.primary,
          backgroundColor: tokens.surface,
          boxShadow: "0 1px 2px rgba(15, 23, 42, 0.04)",
          "&:hover": {
            borderColor: tokens.borderStrong,
            backgroundColor: tokens.surfaceHover,
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
            backgroundColor: "rgba(15, 23, 42, 0.05)",
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
          backgroundColor: tokens.surface,
          transition: "border-color 140ms ease, box-shadow 140ms ease",
          "& .MuiOutlinedInput-notchedOutline": {
            borderColor: tokens.border,
            borderWidth: "1px",
          },
          "&:hover .MuiOutlinedInput-notchedOutline": {
            borderColor: tokens.borderStrong,
          },
          "&.Mui-focused": {
            boxShadow: "0 0 0 3px rgba(37, 99, 235, 0.12)",
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
          backgroundColor: tokens.surface,
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
          backgroundColor: "#0f172a",
          color: "#ffffff",
          fontSize: 11.5,
          fontWeight: 500,
          borderRadius: 6,
          padding: "5px 9px",
          boxShadow: "0 4px 14px rgba(15, 23, 42, 0.2)",
        },
        arrow: {
          color: "#0f172a",
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 16,
          border: `1px solid ${tokens.border}`,
          boxShadow: "0 24px 64px -12px rgba(15, 23, 42, 0.18), 0 0 0 1px rgba(15, 23, 42, 0.04)",
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
          borderRight: "0 !important", // 彻底清除九宫格垂直线
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
          backgroundColor: "rgba(248, 250, 252, 0.85)",
          backdropFilter: "blur(8px)",
          whiteSpace: "nowrap",
          borderBottom: `1px solid ${tokens.border}`,
        },
      },
    },
    MuiTableContainer: {
      styleOverrides: {
        root: {
          scrollbarColor: "rgba(15, 23, 42, 0.15) transparent",
          scrollbarWidth: "thin",
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          transition: "background-color 100ms ease",
          "&.MuiTableRow-hover:hover": {
            backgroundColor: "rgba(241, 245, 249, 0.65) !important",
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
          boxShadow: "0 1px 3px rgba(15, 23, 42, 0.04)",
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
          boxShadow: "0 2px 4px 0 rgba(0, 35, 11, 0.2)",
          width: 16,
          height: 16,
          borderRadius: 8,
          transition: "width 120ms ease, transform 120ms ease",
        },
        track: {
          borderRadius: 20 / 2,
          opacity: 1,
          backgroundColor: "rgba(15, 23, 42, 0.16)",
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
          backgroundColor: "rgba(15, 23, 42, 0.08)",
        },
      },
    },
  },
}, zhCN);
