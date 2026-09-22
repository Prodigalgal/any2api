"use client";

import { Box, Paper, Stack, Typography, type SxProps, type Theme } from "@mui/material";
import type { ReactNode } from "react";
import { tokens } from "@/theme/theme";

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
        px: { xs: 2, sm: 3.5, xl: 4 },
        py: { xs: 2.5, sm: 3.5, xl: 4 },
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
        minHeight: 52,
        mb: { xs: 2.5, sm: 3.25 },
        alignItems: { xs: "flex-start", sm: "center" },
        justifyContent: "space-between",
        gap: { xs: 1.5, sm: 3 },
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography
          variant="h4"
          sx={{
            fontWeight: 750,
            fontSize: { xs: "1.375rem", sm: "1.625rem" },
            letterSpacing: "-0.03em",
            color: "text.primary",
          }}
        >
          {title}
        </Typography>
        <Typography
          color="text.secondary"
          sx={{
            mt: 0.5,
            fontSize: { xs: 12.5, sm: 13 },
            fontWeight: 450,
            lineHeight: 1.5,
            letterSpacing: "-0.005em",
          }}
        >
          {description}
        </Typography>
      </Box>
      {actions ? (
        <Stack
          direction="row"
          spacing={1.25}
          sx={{
            alignItems: "center",
            minHeight: 36,
            flexWrap: "wrap",
            gap: { xs: 1, sm: 0 },
          }}
        >
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
      sx={{
        mb: 2,
        p: { xs: 1.25, sm: 1.5 },
        bgcolor: tokens.surface,
        overflow: "hidden",
        borderRadius: "12px",
        borderColor: tokens.border,
        boxShadow: tokens.shadow.sm,
      }}
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
      sx={[
        {
          overflow: "hidden",
          position: "relative",
          borderRadius: "14px",
          borderColor: tokens.border,
          bgcolor: tokens.surface,
          boxShadow: tokens.shadow.card,
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Paper>
  );
}
