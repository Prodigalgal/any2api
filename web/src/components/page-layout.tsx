"use client";

import { Box, Paper, Stack, Typography, type SxProps, type Theme } from "@mui/material";
import type { ReactNode } from "react";

export function PageContainer({
  children,
  maxWidth = 1600,
}: {
  children: ReactNode;
  maxWidth?: number;
}) {
  return (
    <Box
      sx={{
        width: "100%",
        maxWidth,
        minWidth: 0,
        mx: "auto",
        px: { xs: 2, sm: 3, xl: 3.5 },
        py: { xs: 2.25, sm: 2.75, xl: 3.25 },
      }}
    >
      {children}
    </Box>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <Stack
      direction={{ xs: "column", sm: "row" }}
      sx={{
        minHeight: 60,
        mb: { xs: 2.25, sm: 2.75 },
        alignItems: "flex-start",
        justifyContent: "space-between",
        gap: { xs: 1.5, sm: 3 },
      }}
    >
      <Box>
        <Typography variant="h4">{title}</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, fontSize: 13 }}>
          {description}
        </Typography>
      </Box>
      {actions ? (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", minHeight: 36, flexWrap: "wrap" }}>
          {actions}
        </Stack>
      ) : null}
    </Stack>
  );
}

export function ToolbarSurface({ children }: { children: ReactNode }) {
  return (
    <Paper
      component="section"
      variant="outlined"
      sx={{ mb: 2, p: { xs: 1.25, sm: 1.5 }, bgcolor: "background.paper", overflow: "hidden", borderRadius: 2 }}
    >
      {children}
    </Paper>
  );
}

export function DataSurface({
  children,
  sx,
}: {
  children: ReactNode;
  sx?: SxProps<Theme>;
}) {
  return (
    <Paper
      component="section"
      variant="outlined"
      sx={[{ overflow: "hidden", position: "relative", borderRadius: 2, boxShadow: "0 2px 10px rgba(20, 33, 61, 0.025)" }, ...(Array.isArray(sx) ? sx : [sx])]}
    >
      {children}
    </Paper>
  );
}
