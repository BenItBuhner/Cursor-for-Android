// Writes public/audio/cues.json, what scripts/music.py scores the cut to: the tempo and length, where each part of the
// beat sheet starts, the beats the lineup's devices land on, and the frames of the video a finger comes down on.
//
//   npx tsx scripts/cues.ts
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { AT, BPM, beat, DURATION, FPS, HERO, LINEUP, lineupReel, takeFrame, type Reel } from "../src/edit";
import { takes } from "../src/takes";

/** The most take frames one frame of the video moves through that still count as playing, not a cut. */
const PLAYING = 4;

/** The frames of the video between [from] and [until] that a finger comes down on in [reel], as its playback runs over them. */
function taps(reel: Reel, from: number, until: number): number[] {
  const touch = takes[reel.take].touch;
  const out: number[] = [];
  for (let f = from; f < until; f++) {
    const now = takeFrame(reel, f);
    const before = f === from ? now - 1 : takeFrame(reel, f - 1);
    if (now - before < 1 || now - before > PLAYING) continue;
    for (let k = before + 1; k <= now; k++) if (touch[k]?.[2] === 1 && touch[k - 1]?.[2] !== 1) out.push(f);
  }
  return out;
}

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const cues = {
  fps: FPS,
  bpm: BPM,
  frames: DURATION,
  at: AT,
  lands: LINEUP.map((_, i) => AT.lineup + beat(i)),
  taps: [...taps(HERO, AT.start, AT.lineup), ...taps(lineupReel("phone"), AT.lineup, AT.end)],
};
mkdirSync(join(video, "public", "audio"), { recursive: true });
writeFileSync(join(video, "public", "audio", "cues.json"), `${JSON.stringify(cues, null, 2)}\n`);
console.log(`cues: ${cues.frames} frames at ${cues.bpm} bpm, taps at ${cues.taps.join(", ")}`);
