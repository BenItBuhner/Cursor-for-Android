import { staticFile } from "remotion";
import type { DeviceId, TakeId } from "../takes";
import { LOCKED, lockedAt, phoneTake, SHOT } from "./edit";

/**
 * The screens the film puts on the devices' glass, rendered ahead as stills (scripts/reels.ts, Remotion's Reel
 * compositions) because a WebGL texture can't be a DOM overlay: each reel is the take frames one screen shows, in order,
 * with the system bars and the finger drawn over them, or, for the phone at night, its lock screen.
 */
export type ReelId = DeviceId | "phone-lock";
export type ReelSpec = { take: TakeId; lock: boolean; frames: number[] };

function phoneFrames(lock: boolean): number[] {
  const out = new Set<number>(lock ? [] : [LOCKED]);
  for (let f = SHOT.wake.from; f < SHOT.ship.to; f++) if (lockedAt(f) === lock) out.add(phoneTake(f));
  return [...out].sort((a, b) => a - b);
}

export const REELS: Record<ReelId, ReelSpec> = {
  phone: { take: "phone", lock: false, frames: phoneFrames(false) },
  "phone-lock": { take: "phone", lock: true, frames: phoneFrames(true) },
  foldable: { take: "foldable", lock: false, frames: [LOCKED] },
  tablet: { take: "tablet", lock: false, frames: [LOCKED] },
};

/** The still of reel [id] showing take frame [take], or the nearest it has. */
export function reelSrc(id: ReelId, take: number): string {
  const frames = REELS[id].frames;
  let best = 0;
  for (let i = 0; i < frames.length; i++) if (Math.abs(frames[i]! - take) < Math.abs(frames[best]! - take)) best = i;
  return staticFile(`reel/${id}/${String(best).padStart(4, "0")}.jpg`);
}
