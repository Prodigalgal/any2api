"use client";

import {
  AutoAwesomeOutlined,
  BuildOutlined,
  DeleteOutlineOutlined,
  ExpandMoreOutlined,
  KeyOutlined,
  PsychologyOutlined,
  SendOutlined,
  StopCircleOutlined,
  TuneOutlined,
  VisibilityOffOutlined,
  VisibilityOutlined,
  WifiTetheringOutlined,
} from "@mui/icons-material";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  FormControl,
  IconButton,
  InputAdornment,
  InputLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Select,
  Slider,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { api, type ProviderModel } from "@/lib/api";
import { tokens } from "@/theme/theme";

interface ChatMessage {
  id: string;
  role: "system" | "user" | "assistant";
  content: string;
  reasoning?: string;
  toolCalls?: Array<{ id: string; name: string; arguments: string }>;
  timing?: {
    ttftMs?: number;
    totalMs?: number;
    tokensPerSec?: number;
    keepAlives?: number;
  };
}

const DEFAULT_SYSTEM_PROMPT = "你是一个专业、严谨且乐于助人的 AI 助手。";

const DEMO_TOOLS = [
  {
    type: "function",
    function: {
      name: "get_weather",
      description: "查询指定城市当天的天气、温度与降水概率",
      parameters: {
        type: "object",
        properties: {
          city: { type: "string", description: "城市名称，例如 北京、上海、Tokyo" },
          unit: { type: "string", enum: ["celsius", "fahrenheit"], description: "温度单位" },
        },
        required: ["city"],
      },
    },
  },
];

export function Playground() {
  const modelsQuery = useQuery({ queryKey: ["models"], queryFn: api.models });
  const models = useMemo<ProviderModel[]>(() => modelsQuery.data?.data ?? [], [modelsQuery.data?.data]);

  const [selectedModelOverride, setSelectedModelOverride] = useState<string>(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("any2api_playground_model") || "";
    }
    return "";
  });
  const [apiKey, setApiKey] = useState<string>(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("any2api_playground_api_key") || "";
    }
    return "";
  });
  const [showKey, setShowKey] = useState<boolean>(false);
  const [systemPrompt, setSystemPrompt] = useState<string>(DEFAULT_SYSTEM_PROMPT);
  const [temperature, setTemperature] = useState<number>(0.7);
  const [maxTokens, setMaxTokens] = useState<number>(2048);
  const [stream, setStream] = useState<boolean>(true);
  const [enableDemoTools, setEnableDemoTools] = useState<boolean>(false);

  const [input, setInput] = useState<string>("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isGenerating, setIsGenerating] = useState<boolean>(false);
  const [errorNotice, setErrorNotice] = useState<string | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const modelSelectId = useId();

  const selectedModel =
    selectedModelOverride ||
    models.find((m) => m.available)?.id ||
    models[0]?.id ||
    "";

  const handleApiKeyChange = (val: string) => {
    setApiKey(val);
    if (typeof window !== "undefined") {
      localStorage.setItem("any2api_playground_api_key", val);
    }
  };

  const handleModelChange = (val: string) => {
    setSelectedModelOverride(val);
    if (typeof window !== "undefined") {
      localStorage.setItem("any2api_playground_model", val);
    }
  };

  // 自动滚屏到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isGenerating]);

  const activeModelMeta = models.find((m) => m.id === selectedModel);

  const handleStop = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsGenerating(false);
  };

  const handleClear = () => {
    handleStop();
    setMessages([]);
    setErrorNotice(null);
  };

  const handleSend = async () => {
    const trimmed = input.trim();
    if (!trimmed || isGenerating) return;

    if (!selectedModel) {
      setErrorNotice("请先选择要调用的模型");
      return;
    }

    const userMessage: ChatMessage = {
      id: "usr_" + Date.now(),
      role: "user",
      content: trimmed,
    };

    const nextMessages = [...messages, userMessage];
    setMessages(nextMessages);
    setInput("");
    setErrorNotice(null);
    setIsGenerating(true);

    const assistantId = "ast_" + Date.now();
    const assistantMessage: ChatMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      reasoning: "",
      toolCalls: [],
      timing: { keepAlives: 0 },
    };
    setMessages([...nextMessages, assistantMessage]);

    const abortCtrl = new AbortController();
    abortControllerRef.current = abortCtrl;

    const startTime = performance.now();
    let firstTokenTime: number | null = null;
    let keepAliveCount = 0;

    // 构建上下文消息
    const wireMessages = [
      ...(systemPrompt.trim() ? [{ role: "system", content: systemPrompt.trim() }] : []),
      ...nextMessages.map((m) => ({
        role: m.role,
        content: m.content,
      })),
    ];

    const requestBody: Record<string, unknown> = {
      model: selectedModel,
      messages: wireMessages,
      temperature,
      max_tokens: maxTokens,
      stream,
    };

    if (enableDemoTools) {
      requestBody.tools = DEMO_TOOLS;
      requestBody.tool_choice = "auto";
    }

    try {
      const headers: Record<string, string> = {
        "Content-Type": "application/json",
      };
      if (apiKey.trim()) {
        headers.Authorization = `Bearer ${apiKey.trim()}`;
      }

      const response = await fetch("/v1/chat/completions", {
        method: "POST",
        headers,
        body: JSON.stringify(requestBody),
        signal: abortCtrl.signal,
      });

      if (!response.ok) {
        const errorText = await response.text();
        let parsedMessage = errorText;
        try {
          const errObj = JSON.parse(errorText);
          parsedMessage = errObj.error?.message || errObj.message || errorText;
        } catch {
          // Keep raw text
        }
        throw new Error(`HTTP ${response.status}: ${parsedMessage}`);
      }

      if (!stream || !response.body) {
        // 非流式响应
        const data = await response.json();
        const choice = data.choices?.[0];
        const content = choice?.message?.content || "";
        const reasoning = choice?.message?.reasoning_content || choice?.message?.reasoning || "";
        const toolCalls = choice?.message?.tool_calls?.map((tc: { id?: string; function?: { name?: string; arguments?: string } }) => ({
          id: tc.id || "call_unknown",
          name: tc.function?.name || "",
          arguments: typeof tc.function?.arguments === "string" ? tc.function.arguments : JSON.stringify(tc.function?.arguments || {}),
        })) || [];

        const totalMs = Math.round(performance.now() - startTime);

        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantId
              ? {
                  ...msg,
                  content,
                  reasoning,
                  toolCalls,
                  timing: {
                    totalMs,
                    tokensPerSec: content ? Math.round((content.length / (totalMs / 1000)) * 10) / 10 : 0,
                  },
                }
              : msg
          )
        );
      } else {
        // 流式 SSE 响应
        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        let accumulatedContent = "";
        let accumulatedReasoning = "";
        const accumulatedTools: Record<string, { id: string; name: string; arguments: string }> = {};

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() ?? "";

          for (const line of lines) {
            const trimmedLine = line.trim();

            // 监听密集心跳包
            if (trimmedLine.startsWith(": keep-alive") || trimmedLine.startsWith(":keep-alive")) {
              keepAliveCount += 1;
              setMessages((prev) =>
                prev.map((msg) =>
                  msg.id === assistantId
                    ? {
                        ...msg,
                        timing: {
                          ...msg.timing,
                          keepAlives: keepAliveCount,
                        },
                      }
                    : msg
                )
              );
              continue;
            }

            if (!trimmedLine.startsWith("data: ")) continue;
            const payload = trimmedLine.slice(6).trim();
            if (payload === "[DONE]") break;

            try {
              const chunk = JSON.parse(payload);
              if (firstTokenTime === null) {
                firstTokenTime = Math.round(performance.now() - startTime);
              }

              const delta = chunk.choices?.[0]?.delta;
              if (!delta) continue;

              // 文本增量
              if (delta.content) {
                accumulatedContent += delta.content;
              }

              // 思考过程增量
              if (delta.reasoning_content || delta.reasoning) {
                accumulatedReasoning += delta.reasoning_content || delta.reasoning;
              }

              // Tool Calls 增量
              if (delta.tool_calls && Array.isArray(delta.tool_calls)) {
                for (const tc of delta.tool_calls) {
                  const idx = String(tc.index ?? 0);
                  if (!accumulatedTools[idx]) {
                    accumulatedTools[idx] = {
                      id: tc.id || `call_${idx}`,
                      name: tc.function?.name || "",
                      arguments: "",
                    };
                  }
                  if (tc.function?.name && !accumulatedTools[idx].name) {
                    accumulatedTools[idx].name = tc.function.name;
                  }
                  if (tc.function?.arguments) {
                    accumulatedTools[idx].arguments += tc.function.arguments;
                  }
                }
              }

              const nowTotalMs = Math.round(performance.now() - startTime);
              const toolCallsList = Object.values(accumulatedTools);

              setMessages((prev) =>
                prev.map((msg) =>
                  msg.id === assistantId
                    ? {
                        ...msg,
                        content: accumulatedContent,
                        reasoning: accumulatedReasoning,
                        toolCalls: toolCallsList,
                        timing: {
                          ttftMs: firstTokenTime ?? undefined,
                          totalMs: nowTotalMs,
                          keepAlives: keepAliveCount,
                          tokensPerSec:
                            nowTotalMs > 0
                              ? Math.round((accumulatedContent.length / (nowTotalMs / 1000)) * 10) / 10
                              : 0,
                        },
                      }
                    : msg
                )
              );
            } catch {
              // 容忍残缺帧
            }
          }
        }
      }
    } catch (err: unknown) {
      if ((err as Error).name === "AbortError") {
        // 用户主动停止生成
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantId
              ? {
                  ...msg,
                  content: (msg.content || "") + "\n\n*(生成已被手动终止)*",
                }
              : msg
          )
        );
      } else {
        const errorMsg = (err as Error).message || "调用接口失败";
        setErrorNotice(errorMsg);
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantId
              ? {
                  ...msg,
                  content: `⚠️ 请求异常: ${errorMsg}`,
                }
              : msg
          )
        );
      }
    } finally {
      setIsGenerating(false);
      abortControllerRef.current = null;
    }
  };

  return (
    <Box sx={{ p: { xs: 2, md: 3 }, height: "calc(100vh - 88px)", display: "flex", flexDirection: "column" }}>
      {/* 顶部标题与模型栏 */}
      <Box
        sx={{
          pb: 2,
          borderBottom: `1px solid ${tokens.border}`,
          display: "flex",
          flexDirection: { xs: "column", md: "row" },
          alignItems: { xs: "stretch", md: "center" },
          justifyContent: "space-between",
          gap: 2,
        }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <Box
            sx={{
              width: 38,
              height: 38,
              borderRadius: 2,
              bgcolor: tokens.primary.light,
              color: tokens.primary.main,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <AutoAwesomeOutlined fontSize="small" />
          </Box>
          <Box>
            <Typography variant="h6" sx={{ fontWeight: 700, fontSize: "1.1rem", lineHeight: 1.2 }}>
              模型操练台 (Playground)
            </Typography>
            <Typography variant="body2" sx={{ color: tokens.text.secondary, fontSize: "0.8rem" }}>
              全协议在线实时联调 · 密集心跳保活 · 虚拟 Tool Calling 仿真
            </Typography>
          </Box>
        </Box>

        {/* 模型选择与控制 */}
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
          <FormControl size="small" sx={{ minWidth: 260 }}>
            <InputLabel id={modelSelectId}>选择目标模型</InputLabel>
            <Select
              labelId={modelSelectId}
              label="选择目标模型"
              value={selectedModel}
              onChange={(e) => handleModelChange(e.target.value)}
              disabled={isGenerating || modelsQuery.isLoading}
              sx={{ borderRadius: 2 }}
            >
              {models.map((model) => (
                <MenuItem key={model.id} value={model.id}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                    <Typography sx={{ fontWeight: 600, fontSize: "0.85rem" }}>{model.id}</Typography>
                    <Typography variant="caption" sx={{ color: tokens.text.muted, fontSize: "0.75rem" }}>
                      ({model.provider_name})
                    </Typography>
                  </Box>
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          {activeModelMeta && (
            <Box sx={{ display: { xs: "none", lg: "flex" }, alignItems: "center", gap: 0.5 }}>
              {activeModelMeta.streaming && (
                <Chip label="SSE 流式" size="small" color="primary" variant="outlined" sx={{ height: 24, fontSize: "0.7rem" }} />
              )}
              {activeModelMeta.reasoning?.supported && (
                <Chip label="思考推理" size="small" color="secondary" variant="outlined" sx={{ height: 24, fontSize: "0.7rem" }} />
              )}
              {activeModelMeta.tools?.supported && (
                <Chip label="Function Call" size="small" color="success" variant="outlined" sx={{ height: 24, fontSize: "0.7rem" }} />
              )}
            </Box>
          )}

          <Tooltip title="清空对话记录">
            <IconButton onClick={handleClear} disabled={messages.length === 0 || isGenerating} size="small" sx={{ color: tokens.text.muted }}>
              <DeleteOutlineOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>

      {errorNotice && (
        <Alert severity="error" onClose={() => setErrorNotice(null)} sx={{ mt: 1.5, mb: 1, borderRadius: 2 }}>
          {errorNotice}
        </Alert>
      )}

      {/* 主布局：左侧消息区域 + 右侧参数侧边栏 */}
      <Box sx={{ flex: 1, display: "flex", gap: 2.5, mt: 2, minHeight: 0 }}>
        {/* 左侧消息主窗口 */}
        <Paper
          elevation={0}
          sx={{
            flex: 1,
            display: "flex",
            flexDirection: "column",
            border: `1px solid ${tokens.border}`,
            borderRadius: 3,
            bgcolor: tokens.surface,
            overflow: "hidden",
          }}
        >
          {/* 消息历史滚动区 */}
          <Box
            sx={{
              flex: 1,
              overflowY: "auto",
              p: 2.5,
              display: "flex",
              flexDirection: "column",
              gap: 2,
            }}
          >
            {messages.length === 0 ? (
              <Box
                sx={{
                  flex: 1,
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  justifyContent: "center",
                  color: tokens.text.muted,
                  textAlign: "center",
                  py: 6,
                }}
              >
                <Box
                  sx={{
                    width: 56,
                    height: 56,
                    borderRadius: "50%",
                    bgcolor: tokens.canvasSubtle,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    mb: 2,
                    color: tokens.text.secondary,
                  }}
                >
                  <TuneOutlined fontSize="medium" />
                </Box>
                <Typography variant="subtitle1" sx={{ fontWeight: 600, color: tokens.text.primary, mb: 0.5 }}>
                  随时发起一次推理
                </Typography>
                <Typography variant="body2" sx={{ maxWidth: 420, mb: 3 }}>
                  在右侧配置 API Key 与推理参数，在下方输入您的问题。支持实时流式输出、长流心跳保活检测与工具调用。
                </Typography>
                <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", justifyContent: "center" }}>
                  {[
                    "用 Python 写一个高效的 LRU Cache",
                    "请调用天气工具查询上海今天的天气",
                    "解释一下大型语言模型的 KV Cache 机制",
                  ].map((preset) => (
                    <Button
                      key={preset}
                      variant="outlined"
                      size="small"
                      onClick={() => setInput(preset)}
                      sx={{
                        borderRadius: 2,
                        textTransform: "none",
                        color: tokens.text.secondary,
                        borderColor: tokens.border,
                        bgcolor: tokens.canvas,
                        fontSize: "0.8rem",
                        "&:hover": { bgcolor: tokens.surfaceHover },
                      }}
                    >
                      {preset}
                    </Button>
                  ))}
                </Box>
              </Box>
            ) : (
              messages.map((m) => (
                <Box
                  key={m.id}
                  sx={{
                    display: "flex",
                    justifyContent: m.role === "user" ? "flex-end" : "flex-start",
                  }}
                >
                  <Box
                    sx={{
                      maxWidth: { xs: "90%", md: "82%" },
                      bgcolor: m.role === "user" ? tokens.primary.light : tokens.canvas,
                      border: `1px solid ${m.role === "user" ? tokens.primary.main + "20" : tokens.borderSubtle}`,
                      borderRadius: 2.5,
                      p: 2,
                    }}
                  >
                    {/* Assistant 角色标头与时延监控 */}
                    {m.role === "assistant" && (
                      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, mb: 1 }}>
                        <Box sx={{ display: "flex", alignItems: "center", gap: 0.8 }}>
                          <Box
                            sx={{
                              width: 8,
                              height: 8,
                              borderRadius: "50%",
                              bgcolor: tokens.status.emerald.main,
                            }}
                          />
                          <Typography variant="caption" sx={{ fontWeight: 700, color: tokens.text.primary }}>
                            {selectedModel || "Assistant"}
                          </Typography>
                        </Box>

                        {m.timing && (
                          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                            {m.timing.ttftMs !== undefined && (
                              <Chip
                                label={`TTFT: ${m.timing.ttftMs}ms`}
                                size="small"
                                sx={{ height: 20, fontSize: "0.68rem", bgcolor: tokens.surface }}
                              />
                            )}
                            {m.timing.totalMs !== undefined && (
                              <Chip
                                label={`耗时: ${(m.timing.totalMs / 1000).toFixed(2)}s`}
                                size="small"
                                sx={{ height: 20, fontSize: "0.68rem", bgcolor: tokens.surface }}
                              />
                            )}
                            {m.timing.keepAlives !== undefined && m.timing.keepAlives > 0 && (
                              <Chip
                                icon={<WifiTetheringOutlined sx={{ fontSize: "0.8rem !important" }} />}
                                label={`心跳: ${m.timing.keepAlives}`}
                                size="small"
                                color="success"
                                variant="outlined"
                                sx={{ height: 20, fontSize: "0.68rem" }}
                              />
                            )}
                          </Box>
                        )}
                      </Box>
                    )}

                    {/* 思考过程展开卡片 */}
                    {m.reasoning && (
                      <Accordion
                        defaultExpanded={isGenerating}
                        sx={{
                          mb: 1.5,
                          borderRadius: "8px !important",
                          bgcolor: tokens.surface,
                          border: `1px solid ${tokens.status.amber.border}`,
                          "&:before": { display: "none" },
                        }}
                      >
                        <AccordionSummary expandIcon={<ExpandMoreOutlined sx={{ fontSize: "1rem" }} />}>
                          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                            <PsychologyOutlined sx={{ fontSize: "1rem", color: tokens.status.amber.main }} />
                            <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.status.amber.text }}>
                              思考过程 (Reasoning Process)
                            </Typography>
                          </Box>
                        </AccordionSummary>
                        <AccordionDetails sx={{ pt: 0, pb: 1.5 }}>
                          <Typography
                            variant="body2"
                            sx={{
                              fontSize: "0.82rem",
                              color: tokens.text.secondary,
                              whiteSpace: "pre-wrap",
                              fontFamily: "monospace",
                              lineHeight: 1.6,
                            }}
                          >
                            {m.reasoning}
                          </Typography>
                        </AccordionDetails>
                      </Accordion>
                    )}

                    {/* 正文内容展示 */}
                    {m.content && (
                      <Typography
                        variant="body2"
                        sx={{
                          fontSize: "0.9rem",
                          lineHeight: 1.65,
                          color: m.role === "user" ? tokens.primary.dark : tokens.text.primary,
                          whiteSpace: "pre-wrap",
                          wordBreak: "break-word",
                        }}
                      >
                        {m.content}
                      </Typography>
                    )}

                    {/* Tool Call 触发卡片 */}
                    {m.toolCalls && m.toolCalls.length > 0 && (
                      <Box sx={{ display: "flex", flexDirection: "column", gap: 1, mt: 1.5 }}>
                        {m.toolCalls.map((tc, idx) => (
                          <Paper
                            key={tc.id || idx}
                            elevation={0}
                            sx={{
                              p: 1.5,
                              borderRadius: 2,
                              bgcolor: tokens.surface,
                              border: `1px solid ${tokens.status.emerald.border}`,
                            }}
                          >
                            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", mb: 0.5 }}>
                              <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                                <BuildOutlined sx={{ fontSize: "0.9rem", color: tokens.status.emerald.main }} />
                                <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: "0.82rem" }}>
                                  函数调用: {tc.name}
                                </Typography>
                              </Box>
                              <Chip label={tc.id} size="small" sx={{ height: 18, fontSize: "0.65rem", fontFamily: "monospace" }} />
                            </Box>
                            <Box
                              component="pre"
                              sx={{
                                m: 0,
                                p: 1,
                                borderRadius: 1.5,
                                bgcolor: tokens.canvasSubtle,
                                fontSize: "0.75rem",
                                fontFamily: "monospace",
                                overflowX: "auto",
                              }}
                            >
                              {tc.arguments}
                            </Box>
                          </Paper>
                        ))}
                      </Box>
                    )}
                  </Box>
                </Box>
              ))
            )}
            <div ref={messagesEndRef} />
          </Box>

          {isGenerating && <LinearProgress sx={{ height: 2 }} />}

          {/* 底部发送输入区域 */}
          <Box sx={{ p: 2, borderTop: `1px solid ${tokens.border}`, bgcolor: tokens.surface }}>
            <TextField
              fullWidth
              multiline
              maxRows={6}
              placeholder="输入问题并按 Enter 发送 (Shift + Enter 换行)..."
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              disabled={isGenerating}
              slotProps={{
                input: {
                  sx: {
                    borderRadius: 2.5,
                    fontSize: "0.9rem",
                    bgcolor: tokens.canvas,
                  },
                  endAdornment: (
                    <InputAdornment position="end">
                      {isGenerating ? (
                        <Button
                          variant="contained"
                          color="error"
                          size="small"
                          onClick={handleStop}
                          startIcon={<StopCircleOutlined />}
                          sx={{ borderRadius: 2, textTransform: "none", px: 2 }}
                        >
                          停止
                        </Button>
                      ) : (
                        <IconButton
                          color="primary"
                          disabled={!input.trim() || isGenerating}
                          onClick={handleSend}
                          sx={{
                            bgcolor: tokens.primary.main,
                            color: "#fff",
                            "&:hover": { bgcolor: tokens.primary.dark },
                            "&.Mui-disabled": { bgcolor: tokens.canvasSubtle, color: tokens.text.muted },
                          }}
                        >
                          <SendOutlined fontSize="small" />
                        </IconButton>
                      )}
                    </InputAdornment>
                  ),
                },
              }}
            />
          </Box>
        </Paper>

        {/* 右侧参数控制面板 */}
        <Paper
          elevation={0}
          sx={{
            width: { xs: "100%", md: 320 },
            border: `1px solid ${tokens.border}`,
            borderRadius: 3,
            p: 2.5,
            bgcolor: tokens.surface,
            display: { xs: "none", md: "flex" },
            flexDirection: "column",
            gap: 2.5,
            overflowY: "auto",
          }}
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: "0.9rem" }}>
            推理参数配置
          </Typography>

          {/* API Key 输入框 */}
          <Box>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 0.5 }}>
              <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.secondary }}>
                API 授权密钥 (Bearer Key)
              </Typography>
              <Button
                variant="text"
                size="small"
                href="/api-keys"
                sx={{ fontSize: "0.75rem", textTransform: "none", p: 0, minWidth: "auto" }}
              >
                分发管理
              </Button>
            </Box>
            <TextField
              fullWidth
              size="small"
              type={showKey ? "text" : "password"}
              placeholder="sk-any2api-... (自动留存)"
              value={apiKey}
              onChange={(e) => handleApiKeyChange(e.target.value)}
              slotProps={{
                input: {
                  startAdornment: (
                    <InputAdornment position="start">
                      <KeyOutlined fontSize="small" sx={{ color: tokens.text.muted }} />
                    </InputAdornment>
                  ),
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton size="small" onClick={() => setShowKey(!showKey)}>
                        {showKey ? <VisibilityOffOutlined fontSize="small" /> : <VisibilityOutlined fontSize="small" />}
                      </IconButton>
                    </InputAdornment>
                  ),
                  sx: { borderRadius: 2, fontSize: "0.85rem" },
                },
              }}
            />
          </Box>

          <Divider sx={{ borderColor: tokens.borderSubtle }} />

          {/* System Prompt */}
          <Box>
            <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.secondary, mb: 0.5, display: "block" }}>
              系统设定 (System Instruction)
            </Typography>
            <TextField
              fullWidth
              multiline
              rows={3}
              size="small"
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
              placeholder="设置 AI 角色与指令..."
              slotProps={{ input: { sx: { borderRadius: 2, fontSize: "0.82rem" } } }}
            />
          </Box>

          {/* Temperature 滑块 */}
          <Box>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.secondary }}>
                采样温度 (Temperature)
              </Typography>
              <Typography variant="caption" sx={{ fontWeight: 700, fontFamily: "monospace" }}>
                {temperature}
              </Typography>
            </Box>
            <Slider
              value={temperature}
              min={0}
              max={2}
              step={0.1}
              onChange={(_, val) => setTemperature(val as number)}
              size="small"
              sx={{ color: tokens.primary.main }}
            />
          </Box>

          {/* Max Tokens 滑块 */}
          <Box>
            <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.secondary }}>
                单次生成上限 (Max Tokens)
              </Typography>
              <Typography variant="caption" sx={{ fontWeight: 700, fontFamily: "monospace" }}>
                {maxTokens}
              </Typography>
            </Box>
            <Slider
              value={maxTokens}
              min={256}
              max={8192}
              step={256}
              onChange={(_, val) => setMaxTokens(val as number)}
              size="small"
              sx={{ color: tokens.primary.main }}
            />
          </Box>

          <Divider sx={{ borderColor: tokens.borderSubtle }} />

          {/* 流式传输开关 */}
          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.primary, display: "block" }}>
                SSE 流式增量传输
              </Typography>
              <Typography variant="caption" sx={{ color: tokens.text.muted, fontSize: "0.75rem" }}>
                首字节心跳保活与渐进展示
              </Typography>
            </Box>
            <Switch checked={stream} onChange={(e) => setStream(e.target.checked)} size="small" />
          </Box>

          {/* 虚拟 Tool Calling 演练开关 */}
          <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600, color: tokens.text.primary, display: "block" }}>
                注入 Tool Calling 示例
              </Typography>
              <Typography variant="caption" sx={{ color: tokens.text.muted, fontSize: "0.75rem" }}>
                测试天气查询函数的虚拟转译
              </Typography>
            </Box>
            <Switch checked={enableDemoTools} onChange={(e) => setEnableDemoTools(e.target.checked)} size="small" color="success" />
          </Box>

          <Box sx={{ mt: "auto", p: 1.5, borderRadius: 2, bgcolor: tokens.canvasSubtle }}>
            <Typography variant="caption" sx={{ color: tokens.text.muted, fontSize: "0.72rem", lineHeight: 1.5, display: "block" }}>
              💡 提示：在注入 Tool Calling 模式下，即便后端上游 Provider 不具备原生 Function Calling 支持，Any2API 转译引擎也将自动通过 Prompt 注入与流式语法解析完成标准化转译。
            </Typography>
          </Box>
        </Paper>
      </Box>
    </Box>
  );
}
