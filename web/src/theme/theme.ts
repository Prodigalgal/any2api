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
    background: { default: "#f4f6f7", paper: "#ffffff" },
    text: { primary: "#172126", secondary: "#5d6a70" },
    divider: "#dfe5e7"
  },
  shape: { borderRadius: 6 },
  typography: {
    fontFamily: 'Inter, "Segoe UI", "Microsoft YaHei", sans-serif',
    h4: { fontSize: "1.5rem", fontWeight: 700, lineHeight: 1.25 },
    h6: { fontSize: "1rem", fontWeight: 700, lineHeight: 1.4 },
    body1: { fontSize: "0.875rem", lineHeight: 1.55 },
    body2: { fontSize: "0.8125rem", lineHeight: 1.5 },
    button: { fontSize: "0.8125rem", fontWeight: 650, textTransform: "none" }
  },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { minHeight: 34 } }
    },
    MuiPaper: {
      styleOverrides: { root: { backgroundImage: "none" } }
    },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: "#e5eaec", paddingTop: 11, paddingBottom: 11 },
        head: { color: "#5d6a70", fontSize: "0.75rem", fontWeight: 700, backgroundColor: "#f8fafb" }
      }
    }
  }
});

