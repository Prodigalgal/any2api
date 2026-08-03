"use client";

import { createTheme } from "@mui/material/styles";

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: "#087f8c", dark: "#075f69", light: "#d9f1f2" },
    secondary: { main: "#3454d1" },
    success: { main: "#16825d" },
    warning: { main: "#b66a00" },
    error: { main: "#c43d32" },
    background: { default: "#f3f5f6", paper: "#ffffff" },
    text: { primary: "#172126", secondary: "#5b696f" },
    divider: "#dce3e5"
  },
  shape: { borderRadius: 6 },
  typography: {
    fontFamily: 'Inter, "Segoe UI", "Microsoft YaHei", sans-serif',
    h4: { fontSize: "1.5rem", fontWeight: 750, lineHeight: 1.25, letterSpacing: 0 },
    h6: { fontSize: "1rem", fontWeight: 700, lineHeight: 1.4 },
    body1: { fontSize: "0.875rem", lineHeight: 1.55 },
    body2: { fontSize: "0.8125rem", lineHeight: 1.5 },
    button: { fontSize: "0.8125rem", fontWeight: 650, textTransform: "none", letterSpacing: 0 }
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        "html, body": { minWidth: 1280 },
        body: { fontFeatureSettings: '"tnum" 1, "cv11" 1' },
        "*": { boxSizing: "border-box" },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { minHeight: 36, borderRadius: 6, paddingInline: 14 },
        startIcon: { marginRight: 6, "& > *:first-of-type": { fontSize: 18 } },
      }
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: "none" },
        outlined: { borderColor: "#dce3e5" },
      }
    },
    MuiIconButton: {
      styleOverrides: {
        root: { width: 32, height: 32, borderRadius: 6 },
      },
    },
    MuiTextField: {
      defaultProps: { size: "small" },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: { minHeight: 38, borderRadius: 6, backgroundColor: "#ffffff" },
        input: { paddingTop: 9, paddingBottom: 9, fontSize: 13 },
      },
    },
    MuiInputLabel: {
      styleOverrides: { root: { fontSize: 13 } },
    },
    MuiChip: {
      styleOverrides: {
        root: { height: 24, borderRadius: 4, fontSize: 11.5, fontWeight: 650 },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true },
    },
    MuiDialog: {
      styleOverrides: { paper: { borderRadius: 8 } },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          height: 48,
          borderColor: "#e5eaec",
          padding: "8px 14px",
          fontSize: "0.78125rem",
        },
        head: {
          height: 42,
          color: "#536168",
          fontSize: "0.71875rem",
          fontWeight: 750,
          backgroundColor: "#f7f9fa",
          whiteSpace: "nowrap",
        }
      }
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          "&.MuiTableRow-hover:hover": { backgroundColor: "#f5faf9" },
        },
      },
    },
    MuiTablePagination: {
      styleOverrides: {
        toolbar: { minHeight: 52, paddingInline: 14 },
        selectLabel: { fontSize: 12 },
        displayedRows: { fontSize: 12 },
      }
    }
  }
});
