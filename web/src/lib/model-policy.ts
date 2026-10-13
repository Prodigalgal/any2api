export function isInitialVerificationModel(modelId: string): boolean {
  const upstreamId = modelId.slice(modelId.lastIndexOf("/") + 1).replace(/^minimax-/i, "");
  return !/(max|pro|sonnet|opus|o1|o3|thinking|reasoner|r1|ultra|flagship|-image|-video|-i2v|-t2v|-t2i|-edit|flux-|wan-|wan2|veo-|kling-|sora)/i.test(upstreamId);
}
