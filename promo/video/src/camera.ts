import { AT, HEROES, SHADE, THEME, takeFrame, whenShown, type Reel } from "./edit";
import { easeInOut, lerp, progress } from "./math";
import { mark, stream, takes, type Theme } from "./takes";

export type Framing = "wide" | "tall";

/**
 * Where the camera holds from frame [at], eased there over [dur] frames from the shot before: the phone at [zoom]
 * times its resting size, with the point [fx, fy] of its screen (fractions of its width and height) at [x, y] of the
 * frame.
 */
export type Shot = { at: number; dur: number; zoom: number; fx: number; fy: number; x: number; y: number };

/** Where the tall frame has the top of the phone's screen, the headline standing over it: a fraction of its height. */
export const TALL_TOP = 460 / 1920;

/**
 * The phone's shots, from where [reel] shows what they are waiting on. The home screen from the Project's lift to the
 * prompt's send; in on the chat once it has opened, for the edits and the diff; and once the diff folds away, back out
 * to the whole phone, the composer and the notification shade in it, for the rest. The tall frame keeps the screen's
 * top under the headline and only pulls back.
 */
function shotsOf(reel: Reel): Record<Framing, Shot[]> {
  const opened = whenShown(reel, mark(reel.take, "the chat") + 21);
  const folded = whenShown(reel, mark(reel.take, "fold") + 14);
  return {
    wide: [
      { at: AT.organize, dur: 0, zoom: 1.85, fx: 0.5, fy: 0.52, x: 0.69, y: 0.5 },
      { at: opened, dur: 40, zoom: 1.8, fx: 0.5, fy: 0.36, x: 0.69, y: 0.5 },
      { at: folded, dur: 50, zoom: 1.08, fx: 0.5, fy: 0.5, x: 0.69, y: 0.5 },
    ],
    tall: [
      { at: AT.organize, dur: 0, zoom: 1.3, fx: 0.5, fy: 0, x: 0.5, y: TALL_TOP },
      { at: folded, dur: 50, zoom: 1, fx: 0.5, fy: 0, x: 0.5, y: TALL_TOP },
    ],
  };
}

/** The most of the screen that may change from one take frame to the next under a moving camera. */
const STILL = 0.03;

/** Throws if a shot takes over before the one before it has settled, or moves over the app moving. */
function checkShots(reel: Reel, shots: Shot[]) {
  shots.forEach((shot, i) => {
    const next = shots[i + 1];
    if (next && next.at < shot.at + shot.dur) throw new Error(`The shot at ${next.at} takes over before the one at ${shot.at} settles`);
    for (let f = shot.at; f < shot.at + shot.dur; f++) {
      const k = takeFrame(reel, f);
      const moved = takes[reel.take].motion[k] ?? 0;
      if (moved > STILL) throw new Error(`${reel.take}: the camera moves at ${f} over take frame ${k}, which changes ${moved} of the screen`);
    }
  });
}

export const HERO_SHOTS: Record<Theme, Record<Framing, Shot[]>> = { dark: shotsOf(HEROES.dark), light: shotsOf(HEROES.light) };
for (const theme of ["dark", "light"] as const) for (const shots of Object.values(HERO_SHOTS[theme])) checkShots(HEROES[theme], shots);

export type Camera = Omit<Shot, "at" | "dur">;

/** The camera at frame [f]: each shot eased into from the one before, its zoom by equal ratios. */
export function cameraAt(shots: Shot[], f: number): Camera {
  let cam: Camera = shots[0]!;
  for (const shot of shots.slice(1)) {
    if (f < shot.at) break;
    const p = easeInOut(progress(f, shot.at, shot.at + shot.dur));
    cam = {
      zoom: cam.zoom * (shot.zoom / cam.zoom) ** p,
      fx: lerp(cam.fx, shot.fx, p),
      fy: lerp(cam.fy, shot.fy, p),
      x: lerp(cam.x, shot.x, p),
      y: lerp(cam.y, shot.y, p),
    };
  }
  return cam;
}

const still = (take: number) => whenShown(HEROES[THEME], take);
const hero = HEROES[THEME].take;

/** The frames of the 16:9 cut kept as stills: the Android card, each moment at its peak, and the lineup. */
export const STILLS = {
  android: AT.android + 50,
  organize: still(mark(hero, "the lift") + 30),
  dictate: still(mark(hero, "the words") + 12),
  code: still(mark(hero, "diff") + 40),
  steer: still(stream(hero, "hero.adapt").to + 20),
  live: SHADE.pull.at + 80,
  ship: still(mark(hero, "pull request") + 40),
  lineup: AT.end - 30,
};
