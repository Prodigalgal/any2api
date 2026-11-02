# Input image files 1.8.0

Goal: binary multipart image upload and owned, expiring file references for Chat and Responses; preserve inline input and existing per-account/provider upload behavior.

Scope: authenticated upload/metadata/delete; JPEG/PNG/WebP, vision purpose; SHA-256 idempotency; PostgreSQL TTL storage with per-key/global byte and count bounds; explicit 404/409/413/429 errors; resolve references before account acquisition; same-key access; cancelled/failed upload cleanup; add-only migration 036. No public content URLs, remote URL fetch, documents/audio/video, or provider-native file ID reuse.

Contract: Responses `input_image.file_id`; Chat extension `image_url: {file_id: ...}`. Resolve only image content blocks, not tool arguments or schema data. Enforce FILE_UPLOADS and MULTIMODAL_INPUT, then existing provider/model/protocol checks. Raw uploaded objects are provider-independent; native upload remains in the selected account/proxy context. Existing Base64 path remains compatible.

Implementation: input-file repository/service/controller/resolver, gateway controller wiring, migration, tests, API documentation, unified version 1.8.0. Keep JDBC on the configured database executor and no network/file work in transactions. Client upload and streamed inference share a 360-second deadline.

Acceptance: local full backend suite plus private six-real-point HTTP/dual-protocol contract checks; owner isolation, expiration/delete, idempotency conflict, concurrent quota, digest/payload equality; project comparison of real uploads and terminal SSE. Do not commit private images or credentials. Publish immutable versioned artifacts, verify actual image/config/API and keep old client mode as rollback. No provider-account import, refresh or scope broadening.
