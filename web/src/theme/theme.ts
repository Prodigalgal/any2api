"use client";

import { alpha, createTheme } from "@mui/material/styles";

const colors = {
  graphite: "#182126",
  graphiteDeep: "#10171b",
  teal: "#007f7b",
  tealDark: "#00635f",
  cobalt: "#3157c8",
  canvas: "#f5f7f8",
  border: "#dce3e6",
  text: "#172126",
  muted: "#5c6970",
};

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: colors.teal, dark: colors.tealDark, light: "#d9f2ef" },
    secondary: { main: colors.cobalt, dark: "#2342a1", light: "#e7ecff" },
    success: { main: "#16825d", light: "#e4f3ec" },
    warning: { main: "#b66300", light: "#fff0da" },
    error: { main: "#c43c36", light: "#fbe6e4" },
    info: { main: "#2368b5", light: "#e5f0fb" },
    background: { default: colors.canvas, paper: "#ffffff" },
    text: { primary: colors.text, secondary: colors.muted },
    divider: colors.border,
  },
  shape: { borderRadius: 6 },
  typography: {
    fontFamily: 'Inter, "Segoe UI", "Microsoft YaHei", sans-serif',
    h4: { fontSize: "1.5rem", fontWeight: 760, lineHeight: 1.25, letterSpacing: 0 },
    h5: { fontSize: "1.125rem", fontWeight: 740, lineHeight: 1.35, letterSpacing: 0 },
    h6: { fontSize: "0.9375rem", fontWeight: 720, lineHeight: 1.4, letterSpacing: 0 },
    subtitle1: { fontSize: "0.875rem", fontWeight: 700, lineHeight: 1.45 },
    body1: { fontSize: "0.875rem", lineHeight: 1.55, letterSpacing: 0 },
    body2: { fontSize: "0.8125rem", lineHeight: 1.5, letterSpacing: 0 },
    caption: { fontSize: "0.71875rem", lineHeight: 1.45, letterSpacing: 0 },
    button: {
      fontSize: "0.8125rem",
      fontWeight: 680,
      textTransform: "none",
      letterSpacing: 0,
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "html, body": { minWidth: 0, minHeight: "100%" },
        body: {
          fontFeatureSettings: '"tnum" 1, "cv11" 1',
          textRendering: "optimizeLegibility",
        },
        "*": { boxSizing: "border-box" },
        "*:focus-visible": { outline: `2px solid ${alpha(colors.teal, 0.45)}`, outlineOffset: 2 },
        "@media (prefers-reduced-motion: reduce)": {
          "*, *::before, *::after": {
            animationDuration: "0.01ms !important",
            animationIterationCount: "1 !important",
            transitionDuration: "0.01ms !important",
            scrollBehavior: "auto !important",
          },
        },
      },
    },
    MuiAppBar: {
      defaultProps: { color: "inherit", elevation: 0 },
      styleOverrides: { root: { backgroundColor: alpha("#ffffff", 0.96), backdropFilter: "blur(12px)" } },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { minHeight: 36, borderRadius: 6, paddingInline: 14 },
        contained: { boxShadow: "0 1px 1px rgba(16, 24, 28, 0.08)" },
        startIcon: { marginRight: 6, "& > *:first-of-type": { fontSize: 18 } },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: "none" },
        outlined: { borderColor: colors.border },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          width: 34,
          height: 34,
          borderRadius: 6,
          "&:hover": { backgroundColor: alpha(colors.teal, 0.07) },
        },
      },
    },
    MuiTextField: { defaultProps: { size: "small" } },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          minHeight: 38,
          borderRadius: 6,
          backgroundColor: "#ffffff",
          "&.Mui-focused .MuiOutlinedInput-notchedOutline": { borderWidth: 1.5 },
        },
        input: { paddingTop: 9, paddingBottom: 9, fontSize: 13 },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { fontSize: 13 } } },
    MuiChip: {
      styleOverrides: { root: { height: 24, borderRadius: 4, fontSize: 11.5, fontWeight: 670 } },
    },
    MuiTooltip: { defaultProps: { arrow: true, enterDelay: 450 } },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 8,
          border: `1px solid ${colors.border}`,
          boxShadow: "0 24px 70px rgba(15, 27, 32, 0.22)",
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: { root: { padding: "18px 20px", fontSize: 16, fontWeight: 740 } },
    },
    MuiDialogActions: {
      styleOverrides: { root: { minHeight: 64, padding: "12px 20px", borderTop: `1px solid ${colors.border}` } },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { height: 48, borderColor: "#e5eaec", padding: "8px 14px", fontSize: "0.78125rem" },
        head: {
          height: 42,
          color: "#526068",
          fontSize: "0.71875rem",
          fontWeight: 740,
          backgroundColor: "#f8fafb",
          whiteSpace: "nowrap",
        },
      },
    },
    MuiTableRow: {
      styleOverrides: { root: { "&.MuiTableRow-hover:hover": { backgroundColor: "#f3f9f8" } } },
    },
    MuiTablePagination: {
      styleOverrides: {
        toolbar: { minHeight: 52, paddingInline: 14 },
        selectLabel: { fontSize: 12 },
        displayedRows: { fontSize: 12 },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 44 },
        indicator: { height: 2 },
      },
    },
    MuiTab: {
      styleOverrides: { root: { minHeight: 44, padding: "10px 16px", fontSize: 12.5, fontWeight: 680 } },
    },
    MuiAlert: {
      styleOverrides: { root: { borderRadius: 6, alignItems: "center" }, message: { paddingBlock: 3 } },
    },
    MuiLinearProgress: { styleOverrides: { root: { height: 2 } } },
  },
});
