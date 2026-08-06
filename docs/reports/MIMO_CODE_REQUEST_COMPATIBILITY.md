# MiMo Code request compatibility

## Target

- Site: `https://aistudio.xiaomimimo.com`
- Upstream request: `POST /open-apis/bot/chat`
- Downstream client: MiMo Code `0.1.7` with `@ai-sdk/openai-compatible`
- Relevant parameter: OpenAI `max_tokens`

## Observe

- The current page loads `runtime-main.c803f4fa.js`, which resolves the chat bundle to
  `main.d2af961b.chunk.js`.
- Module `29687` builds the official chat payload and calls `OR.completions`.
- Its `modelConfig` contains `model`, `enableThinking`, `webSearchStatus`, and optional
  `temperature`/`topP`; it does not contain an output-token limit.
- `maxCompletionTokens: 65536` occurs in model metadata in module `80032`.
- MiMo Code sends `maxOutputTokens` through its OpenAI-compatible adapter, which becomes
  `max_tokens=128000` on the wire for MiMo models.

## Capture

| Attempt | Result | First divergence |
| --- | --- | --- |
| Legacy `mimo2api-direct` endpoint | `404 Not Found` | Retired endpoint and stale key |
| Any2API `/mimo/v1` endpoint | `400 unsupported_parameter` | MiMo Code always adds `max_tokens` |
| Direct Any2API request without `max_tokens` | `200`, usable output | None |
| Deployed Any2API request with `max_tokens=128000` | `200`, usable output | None |
| MiMo Code from an empty directory | `200`, `MIMOCODE_EMPTY_READY` | None |
| MiMo Code from this repository | `200`, upstream input-too-long reply | Project context raises input from 25,738 to 28,529 tokens |

## Decision

MiMo Web cannot enforce an arbitrary downstream output limit. Any2API therefore accepts a
token ceiling only when it is greater than or equal to the official Web output ceiling. Such a
value is non-binding because the provider cannot exceed it. Lower, binding values remain an
explicit `unsupported_parameter` error and are never silently discarded.

The ceiling is configured by `ANY2API_PROVIDER_MIMO_WEB_OUTPUT_TOKEN_CEILING` and defaults to
`65536`. This isolates upstream drift from request mapping and gives operators a controlled
update point when official model metadata changes.

## Verification

- Unit coverage proves `max_tokens=128000` is accepted and `max_tokens=1024` is rejected.
- Production image `server-sha-e1c69b07d06d7d86e943f03111aa04d9d557cce4` accepts the
  MiMo Code token ceiling and returns a completed response instead of `404` or
  `unsupported_parameter`.
- MiMo Code `0.1.7` produced `MIMOCODE_EMPTY_READY` through Any2API from an empty directory.
- Running MiMo Code from this repository still exceeds the MiMo Web practical input boundary
  because the client includes additional repository context. This is a context-size limitation,
  not an endpoint or request-parameter failure.
