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
        minWidth: 1040,
        mx: "auto",
        px: 3,
        py: 3,
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
      direction="row"
      sx={{
        minHeight: 56,
        mb: 2.5,
        alignItems: "flex-start",
        justifyContent: "space-between",
        gap: 3,
      }}
    >
      <Box>
        <Typography variant="h4">{title}</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5, fontSize: 13 }}>
          {description}
        </Typography>
      </Box>
      {actions ? (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", minHeight: 36 }}>
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
      sx={{ mb: 2, p: 1.5, bgcolor: "background.paper" }}
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
      sx={[{ overflow: "hidden", position: "relative" }, ...(Array.isArray(sx) ? sx : [sx])]}
    >
      {children}
    </Paper>
  );
}
