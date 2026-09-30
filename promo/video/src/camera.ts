import { AT, beat, HERO, whenShown } from "./edit";
import { easeInOut, lerp, progress } from "./math";
import { mark } from "./takes";

export type Framing = "wide" | "tall";

/**
 * Where the camera holds from frame [at], reached over [dur] frames from the shot before: the phone at [zoom] times its
 * resting size, with the point [fx, fy] of its screen (fractions of its width and height) at [x, y] of the frame.
 */
export type Shot = { at: number; dur: number; zoom: number; fx: number; fy: number; x: number; y: number };

const shown = (take: number) => whenShown(HERO, take);
const DIFF = shown(mark("phone", "diff")) + 6;
const LATEST = shown(mark("phone", "latest"));
const DETAILS = shown(mark("phone", "details"));
const PULL_REQUEST = shown(mark("phone", "pull request")) + 8;

/** The frames of the 16:9 cut kept as stills: the Android card, the first diff open, the pull request, the lineup. */
export const STILLS = {
  android: AT.android + 50,
  code: AT.steer - 20,
  ship: PULL_REQUEST + 62,
  lineup: AT.end - 30,
};

/** The phone's shots through the four moments: pushed in on what each one is about. */
export const HERO_SHOTS: Record<Framing, Shot[]> = {
  wide: [
    { at: AT.start, dur: 0, zoom: 1, fx: 0.5, fy: 0.5, x: 0.69, y: 0.5 },
    { at: AT.start + 26, dur: 44, zoom: 2, fx: 0.5, fy: 0.15, x: 0.69, y: 0.38 },
    { at: AT.send + 6, dur: 30, zoom: 2, fx: 0.5, fy: 0.12, x: 0.69, y: 0.34 },
    { at: AT.code, dur: 26, zoom: 2.05, fx: 0.5, fy: 0.15, x: 0.69, y: 0.35 },
    { at: DIFF, dur: 40, zoom: 2, fx: 0.5, fy: 0.4, x: 0.69, y: 0.5 },
    { at: AT.steer, dur: 26, zoom: 2, fx: 0.5, fy: 0.88, x: 0.69, y: 0.62 },
    { at: LATEST + 4, dur: 30, zoom: 2.05, fx: 0.5, fy: 0.8, x: 0.69, y: 0.55 },
    { at: AT.ship, dur: 22, zoom: 2, fx: 0.5, fy: 0.82, x: 0.69, y: 0.56 },
    { at: DETAILS, dur: 24, zoom: 1.75, fx: 0.5, fy: 0.3, x: 0.69, y: 0.45 },
    { at: PULL_REQUEST, dur: 34, zoom: 2.1, fx: 0.5, fy: 0.74, x: 0.69, y: 0.5 },
  ],
  tall: [
    { at: AT.start, dur: 0, zoom: 1, fx: 0.5, fy: 0.3, x: 0.5, y: 0.56 },
    { at: AT.start + 26, dur: 44, zoom: 1.4, fx: 0.5, fy: 0.15, x: 0.5, y: 0.4 },
    { at: AT.send + 6, dur: 30, zoom: 1.4, fx: 0.5, fy: 0.12, x: 0.5, y: 0.38 },
    { at: AT.code, dur: 26, zoom: 1.45, fx: 0.5, fy: 0.2, x: 0.5, y: 0.42 },
    { at: DIFF, dur: 40, zoom: 1.4, fx: 0.5, fy: 0.45, x: 0.5, y: 0.6 },
    { at: AT.steer, dur: 26, zoom: 1.4, fx: 0.5, fy: 0.86, x: 0.5, y: 0.72 },
    { at: LATEST + 4, dur: 30, zoom: 1.45, fx: 0.5, fy: 0.8, x: 0.5, y: 0.67 },
    { at: AT.ship, dur: 22, zoom: 1.4, fx: 0.5, fy: 0.8, x: 0.5, y: 0.66 },
    { at: DETAILS, dur: 24, zoom: 1.3, fx: 0.5, fy: 0.35, x: 0.5, y: 0.55 },
    { at: PULL_REQUEST, dur: 34, zoom: 1.5, fx: 0.5, fy: 0.74, x: 0.5, y: 0.62 },
  ],
};

// A shot that took over mid-move would start from where the one before was headed, not from where it had got to.
for (const shots of Object.values(HERO_SHOTS)) {
  shots.forEach((shot, i) => {
    const next = shots[i + 1];
    if (next && next.at < shot.at + shot.dur) throw new Error(`The shot at ${next.at} takes over before the one at ${shot.at} settles`);
  });
}

export type Camera = Omit<Shot, "at" | "dur">;

/** Shot [i] as it holds at frame [f]: pushing in slowly until the next one takes over. */
function holding(shots: Shot[], i: number, f: number): Camera {
  const shot = shots[i]!;
  const next = shots[i + 1];
  const hold = progress(f, shot.at + shot.dur, next ? next.at : shot.at + beat(8));
  return { zoom: shot.zoom * (1 + 0.025 * hold), fx: shot.fx, fy: shot.fy, x: shot.x, y: shot.y };
}

/** The camera at frame [f]: each shot eased into from wherever the one before had got to. */
export function cameraAt(shots: Shot[], f: number): Camera {
  let i = 0;
  while (i + 1 < shots.length && shots[i + 1]!.at <= f) i++;
  const cur = holding(shots, i, f);
  if (i === 0) return cur;
  const shot = shots[i]!;
  const from = holding(shots, i - 1, shot.at);
  const p = easeInOut(progress(f, shot.at, shot.at + shot.dur));
  return {
    zoom: lerp(from.zoom, cur.zoom, p),
    fx: lerp(from.fx, cur.fx, p),
    fy: lerp(from.fy, cur.fy, p),
    x: lerp(from.x, cur.x, p),
    y: lerp(from.y, cur.y, p),
  };
}
