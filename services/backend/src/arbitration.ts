import type { ProgressItemInput } from "./schemas.js";

export type ProgressDecision = "accepted" | "server_kept" | "unchanged";

export interface StoredCandidate {
  offset: number;
  readAtMs: number;
  deviceId: string;
}

export interface EffectiveCandidate extends ProgressItemInput {
  effectiveReadAtMs: number;
  timeAdjusted: boolean;
}

export function effectiveCandidate(
  incoming: ProgressItemInput,
  serverTimeMs: number,
): EffectiveCandidate {
  const maximumAllowed = serverTimeMs + 5 * 60 * 1000;
  if (incoming.readAtMs > maximumAllowed) {
    return {
      ...incoming,
      effectiveReadAtMs: serverTimeMs,
      timeAdjusted: true,
    };
  }
  return {
    ...incoming,
    effectiveReadAtMs: incoming.readAtMs,
    timeAdjusted: false,
  };
}

export function decideCandidate(
  stored: StoredCandidate | undefined,
  incoming: EffectiveCandidate,
  incomingDeviceId: string,
): ProgressDecision {
  if (!stored) return "accepted";
  if (incoming.effectiveReadAtMs > stored.readAtMs) return "accepted";
  if (incoming.effectiveReadAtMs < stored.readAtMs) return "server_kept";
  if (stored.deviceId === incomingDeviceId && stored.offset === incoming.offset) {
    return "unchanged";
  }
  return incomingDeviceId > stored.deviceId ? "accepted" : "server_kept";
}

export function progressRatio(offset: number, fileSize: number): number {
  return offset / fileSize;
}
