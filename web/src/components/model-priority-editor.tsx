"use client";

import {
  AddCircleOutlineOutlined,
  ArrowDownwardOutlined,
  ArrowUpwardOutlined,
  DeleteOutlineOutlined,
  RestartAltOutlined,
} from "@mui/icons-material";
import {
  Box,
  Button,
  Chip,
  IconButton,
  List,
  ListItem,
  ListItemSecondaryAction,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useState } from "react";

export const DEFAULT_DECODING_MODELS = [
  "mimo/mimo-v2.6-pro",
  "glm/GLM-5v-Turbo",
  "arena/gemini-2.5-flash",
];

export const KNOWN_DECODING_MODELS = [
  { value: "mimo/mimo-v2.6-pro", label: "MiMo 2.6 Pro（深度多模态推理·首选）" },
  { value: "glm/GLM-5v-Turbo", label: "GLM-5v Turbo（智谱清言·顶级视觉）" },
  { value: "arena/gemini-2.5-flash", label: "Gemini 2.5 Flash（Arena·极速视觉）" },
  { value: "mimo/mimo-v2.6-flash", label: "MiMo 2.6 Flash（极速视觉）" },
  { value: "arena/gemini-2.5-pro", label: "Gemini 2.5 Pro（Arena·高精度视觉）" },
  { value: "qwen/qwen-vl-max", label: "Qwen VL Max（千问顶级多模态）" },
  { value: "mimo/mimo-v2.5", label: "MiMo 2.5（旧版兼容）" },
];

function getModelLabel(model: string): string {
  const hit = KNOWN_DECODING_MODELS.find((m) => m.value === model);
  return hit ? hit.label : model;
}

export function ModelPriorityEditor({
  models = [],
  onChange,
  disabled = false,
}: {
  models: string[];
  onChange: (nextModels: string[]) => void;
  disabled?: boolean;
}) {
  const [selectedToAdd, setSelectedToAdd] = useState("");
  const [customModel, setCustomModel] = useState("");

  const currentModels =
    models.length > 0 ? models : DEFAULT_DECODING_MODELS;

  const moveUp = (index: number) => {
    if (index <= 0 || disabled) return;
    const next = [...currentModels];
    const temp = next[index - 1];
    next[index - 1] = next[index];
    next[index] = temp;
    onChange(next);
  };

  const moveDown = (index: number) => {
    if (index >= currentModels.length - 1 || disabled) return;
    const next = [...currentModels];
    const temp = next[index + 1];
    next[index + 1] = next[index];
    next[index] = temp;
    onChange(next);
  };

  const remove = (index: number) => {
    if (disabled || currentModels.length <= 1) return;
    const next = currentModels.filter((_, idx) => idx !== index);
    onChange(next);
  };

  const addModel = (modelName: string) => {
    const trimmed = modelName.trim();
    if (!trimmed || currentModels.includes(trimmed) || disabled) return;
    onChange([...currentModels, trimmed]);
    setSelectedToAdd("");
    setCustomModel("");
  };

  const resetToDefault = () => {
    if (disabled) return;
    onChange([...DEFAULT_DECODING_MODELS]);
  };

  return (
    <Box sx={{ width: "100%", mt: 1 }}>
      <Stack
        direction="row"
        sx={{ mb: 1, justifyContent: "space-between", alignItems: "center" }}
      >
        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>
          解码多模型优先级轮询链（遇到不可用或维护自动按序降级切换）
        </Typography>
        <Button
          size="small"
          startIcon={<RestartAltOutlined fontSize="small" />}
          disabled={disabled}
          onClick={resetToDefault}
          sx={{ fontSize: "11px", py: 0.25 }}
        >
          恢复推荐顺序
        </Button>
      </Stack>

      <Paper
        variant="outlined"
        sx={{
          p: 0.5,
          bgcolor: (theme) =>
            theme.palette.mode === "dark" ? "background.default" : "grey.50",
          borderRadius: 1.5,
        }}
      >
        <List dense disablePadding>
          {currentModels.map((model, index) => {
            const isFirst = index === 0;
            const isLast = index === currentModels.length - 1;
            return (
              <ListItem
                key={model}
                sx={{
                  bgcolor: (theme) =>
                    theme.palette.mode === "dark" ? "grey.900" : "background.paper",
                  mb: 0.5,
                  borderRadius: 1,
                  boxShadow: "0 1px 2px rgba(0,0,0,0.05)",
                  pr: 12,
                }}
              >
                <Chip
                  size="small"
                  label={index === 0 ? "首选 #1" : `备用 #${index + 1}`}
                  color={index === 0 ? "primary" : "default"}
                  variant={index === 0 ? "filled" : "outlined"}
                  sx={{ mr: 1.5, fontSize: "11px", fontWeight: 700 }}
                />
                <ListItemText
                  primary={
                    <Typography sx={{ fontSize: "12.5px", fontWeight: 600 }}>
                      {model}
                    </Typography>
                  }
                  secondary={
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ fontSize: "11px" }}
                    >
                      {getModelLabel(model)}
                    </Typography>
                  }
                />
                <ListItemSecondaryAction>
                  <Tooltip title="提高优先级（上移）">
                    <span>
                      <IconButton
                        size="small"
                        disabled={disabled || isFirst}
                        onClick={() => moveUp(index)}
                      >
                        <ArrowUpwardOutlined fontSize="small" />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title="降低优先级（下移）">
                    <span>
                      <IconButton
                        size="small"
                        disabled={disabled || isLast}
                        onClick={() => moveDown(index)}
                      >
                        <ArrowDownwardOutlined fontSize="small" />
                      </IconButton>
                    </span>
                  </Tooltip>
                  <Tooltip title="移除此模型">
                    <span>
                      <IconButton
                        size="small"
                        disabled={disabled || currentModels.length <= 1}
                        onClick={() => remove(index)}
                        color="error"
                      >
                        <DeleteOutlineOutlined fontSize="small" />
                      </IconButton>
                    </span>
                  </Tooltip>
                </ListItemSecondaryAction>
              </ListItem>
            );
          })}
        </List>

        <Stack
          direction={{ xs: "column", sm: "row" }}
          spacing={1}
          sx={{ mt: 1, pt: 1, borderTop: 1, borderColor: "divider", alignItems: "center" }}
        >
          <TextField
            select
            size="small"
            label="添加常用多模态模型"
            value={selectedToAdd}
            disabled={disabled}
            onChange={(e) => {
              const val = e.target.value;
              setSelectedToAdd(val);
              if (val) addModel(val);
            }}
            sx={{ minWidth: 260, flex: 1 }}
          >
            <MenuItem value="">
              <em>-- 请选择要添加的模型 --</em>
            </MenuItem>
            {KNOWN_DECODING_MODELS.filter(
              (m) => !currentModels.includes(m.value)
            ).map((m) => (
              <MenuItem key={m.value} value={m.value}>
                {m.label}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction="row" spacing={0.5} sx={{ flex: 1, width: "100%" }}>
            <TextField
              size="small"
              label="或输入自定义模型 ID"
              placeholder="例如: arena/gemini-1.5-flash"
              value={customModel}
              disabled={disabled}
              onChange={(e) => setCustomModel(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  addModel(customModel);
                }
              }}
              sx={{ flex: 1 }}
            />
            <Button
              variant="outlined"
              size="small"
              disabled={disabled || !customModel.trim()}
              startIcon={<AddCircleOutlineOutlined />}
              onClick={() => addModel(customModel)}
            >
              添加
            </Button>
          </Stack>
        </Stack>
      </Paper>
    </Box>
  );
}
