import { deviceMargin, screenHeight } from "./components/Device";
import { AT, HEROES, MOMENTS, SHADE, THEME, whenShown, type Reel } from "./edit";
import { liveStep, mark, stream, type TakeId, type Theme } from "./takes";

export type Framing = "wide" | "tall";

/** Each framing's frame, in pixels. */
export const FRAME: Record<Framing, { width: number; height: number }> = {
  wide: { width: 1920, height: 1080 },
  tall: { width: 1080, height: 1920 },
};

/** Where the tall frame has the top of the phone's screen at rest, the headline standing over it: a fraction of its height. */
export const TALL_TOP = 460 / 1920;

/**
 * The phone's screen width at zoom 1 in each framing, and how far the eye stands from the frame, in pixels: far enough
 * that a turned phone keeps its shape, near enough that its far edge reads as further away.
 */
export const LENS: Record<Framing, { rest: number; perspective: number }> = {
  wide: { rest: 404, perspective: 2600 },
  tall: { rest: 614, perspective: 2800 },
};

/**
 * The camera on the phone: the phone at [zoom] times its resting size, with the point [fx, fy] of its screen (fractions
 * of its width and height) at [x, y] of the frame, and the phone turned [turn] degrees about its upright axis (its right
 * edge going away as the turn grows), tilted [tilt] about its crosswise one (its top going away) and rolled [roll]
 * clockwise in the frame, all about that point, which the eye looks straight at.
 */
export type Camera = { zoom: number; fx: number; fy: number; x: number; y: number; turn: number; tilt: number; roll: number };

/** Where the camera is at frame [at]. */
type Key = Camera & { at: number };

/**
 * Keys the camera runs through without a cut, from the first's frame until the next shot's, coming into its first key
 * at [enter] times its pace over the stretch after it and leaving its last at [leave] times its pace over the one
 * before: 0 from or to rest, up to 3 for a cubic ease out of or into the key.
 */
type Shot = { keys: Key[]; enter: number; leave: number };

const CHANNELS = ["zoom", "fx", "fy", "x", "y", "turn", "tilt", "roll"] as const;
type Channel = (typeof CHANNELS)[number];

/**
 * A channel of a shot: a cubic through its keys with the slopes Fritsch and Butland give it, which never carries it
 * past a key it is moving between, and brings it to rest only on a key it turns back at.
 */
type Curve = { t: number[]; y: number[]; m: number[] };

function curve(t: number[], y: number[], enter: number, leave: number): Curve {
  const n = t.length;
  const h = (i: number) => t[i + 1]! - t[i]!;
  const d = (i: number) => (y[i + 1]! - y[i]!) / h(i);
  const m = y.map(() => 0);
  for (let i = 1; i < n - 1; i++) {
    const a = d(i - 1);
    const b = d(i);
    if (a * b > 0) m[i] = (3 * (h(i - 1) + h(i))) / ((2 * h(i) + h(i - 1)) / a + (h(i) + 2 * h(i - 1)) / b);
  }
  if (n > 1) {
    m[0] = Math.min(3, enter) * d(0);
    m[n - 1] = Math.min(3, leave) * d(n - 2);
  }
  return { t, y, m };
}

function valueAt(c: Curve, f: number): number {
  const { t, y, m } = c;
  const n = t.length;
  if (n === 1 || f <= t[0]!) return y[0]!;
  if (f >= t[n - 1]!) return y[n - 1]!;
  let i = 0;
  while (f >= t[i + 1]!) i++;
  const h = t[i + 1]! - t[i]!;
  const s = (f - t[i]!) / h;
  const s2 = s * s;
  const s3 = s2 * s;
  return (2 * s3 - 3 * s2 + 1) * y[i]! + (s3 - 2 * s2 + s) * h * m[i]! + (3 * s2 - 2 * s3) * y[i + 1]! + (s3 - s2) * h * m[i + 1]!;
}

/** A framing's camera through the hero: each shot's curves, from the frame it is cut to. */
export type Path = { from: number; curves: Record<Channel, Curve> }[];

function pathOf(shots: Shot[]): Path {
  return shots.map(({ keys, enter, leave }) => {
    keys.forEach((k, i) => {
      if (i > 0 && k.at <= keys[i - 1]!.at) throw new Error(`The camera's key at ${k.at} comes after the one at ${keys[i - 1]!.at}`);
    });
    const t = keys.map((k) => k.at);
    const curves = {} as Record<Channel, Curve>;
    for (const ch of CHANNELS) {
      // Zoom by equal ratios, so a push in reads at one pace however close the camera is.
      const y = keys.map((k) => (ch === "zoom" ? Math.log(k.zoom) : k[ch]));
      curves[ch] = curve(t, y, enter, leave);
    }
    return { from: keys[0]!.at, curves };
  });
}

/** The camera at frame [f] of [path]. */
export function cameraAt(path: Path, f: number): Camera {
  let shot = path[0]!;
  for (const s of path) if (s.from <= f) shot = s;
  const cam = {} as Camera;
  for (const ch of CHANNELS) cam[ch] = valueAt(shot.curves[ch], f);
  cam.zoom = Math.exp(cam.zoom);
  return cam;
}

/** The phone's screen in the frame's plane before it turns: its top left and its size, in pixels. */
export function screenOf(framing: Framing, take: TakeId, cam: Camera) {
  const { width, height } = FRAME[framing];
  const w = LENS[framing].rest * cam.zoom;
  const h = screenHeight(take, w);
  return { left: cam.x * width - cam.fx * w, top: cam.y * height - cam.fy * h, width: w, height: h };
}

type Point = [number, number];

/**
 * Where the point [p] of the phone's plane lands in the frame once the phone is rolled, turned and tilted about the
 * camera's point and seen from in front of it, as CSS's rotateX(tilt) rotateY(turn) rotateZ(roll) under a perspective
 * set there has it.
 */
export function project(framing: Framing, cam: Camera, p: Point): Point {
  const { width, height } = FRAME[framing];
  const ox = cam.x * width;
  const oy = cam.y * height;
  const rad = Math.PI / 180;
  const [r, t, a] = [cam.roll * rad, cam.turn * rad, cam.tilt * rad];
  let x = p[0] - ox;
  let y = p[1] - oy;
  let z = 0;
  [x, y] = [x * Math.cos(r) - y * Math.sin(r), x * Math.sin(r) + y * Math.cos(r)];
  [x, z] = [x * Math.cos(t) + z * Math.sin(t), -x * Math.sin(t) + z * Math.cos(t)];
  [y, z] = [y * Math.cos(a) - z * Math.sin(a), y * Math.sin(a) + z * Math.cos(a)];
  const s = LENS[framing].perspective / (LENS[framing].perspective - z);
  return [ox + x * s, oy + y * s];
}

/** The device's outline in the frame, glass and all: its four corners, clockwise from the top left. */
function outlineOf(framing: Framing, take: TakeId, cam: Camera): Point[] {
  const s = screenOf(framing, take, cam);
  const m = deviceMargin(take, s.width);
  const [l, t, r, b] = [s.left - m, s.top - m, s.left + s.width + m, s.top + s.height + m];
  return ([[l, t], [r, t], [r, b], [l, b]] as Point[]).map((p) => project(framing, cam, p));
}

/** Whether two convex polygons overlap: no edge of either separates them. */
function overlaps(a: Point[], b: Point[]): boolean {
  for (const poly of [a, b]) {
    for (let i = 0; i < poly.length; i++) {
      const [x1, y1] = poly[i]!;
      const [x2, y2] = poly[(i + 1) % poly.length]!;
      const [nx, ny] = [y2 - y1, x1 - x2];
      const pa = a.map(([x, y]) => x * nx + y * ny);
      const pb = b.map(([x, y]) => x * nx + y * ny);
      if (Math.max(...pa) < Math.min(...pb) || Math.max(...pb) < Math.min(...pa)) return false;
    }
  }
  return true;
}

/**
 * Each headline's ink as the cut sets it (Hero's layout, measured off a render): left, top, right and bottom, in pixels
 * of the frame.
 */
const INK: Record<Framing, Record<string, [number, number, number, number]>> = {
  wide: {
    "Organize projects.": [157, 407, 743, 703],
    "Say what you want.": [152, 406, 780, 702],
    "Watch it code.": [155, 407, 711, 672],
    "Queue it. Steer it.": [156, 407, 745, 672],
    "Follow it live.": [161, 407, 699, 672],
    "Ship it.": [156, 484, 594, 624],
  },
  tall: {
    "Organize projects.": [300, 107, 785, 352],
    "Say what you want.": [279, 106, 797, 350],
    "Watch it code.": [314, 107, 772, 326],
    "Queue it. Steer it.": [297, 107, 783, 326],
    "Follow it live.": [322, 107, 767, 326],
    "Ship it.": [358, 171, 720, 286],
  },
};

/** The room the phone keeps from a headline's ink, in pixels. */
const CLEAR: Record<Framing, number> = { wide: 56, tall: 48 };

/** Points over the screen, as fractions of it, whose motion in the frame is the camera's. */
const PROBES: Point[] = [];
for (let i = 0; i <= 4; i++) for (let j = 0; j <= 8; j++) PROBES.push([i / 4, j / 8]);

/**
 * The fastest the camera moves what it shows, and the hardest it speeds up or slows down, in pixels of the frame a frame
 * and a frame per frame: brisk, never a whip, and never a lurch.
 */
const PACE: Record<Framing, { speed: number; accel: number }> = {
  wide: { speed: 40, accel: 5 },
  tall: { speed: 40, accel: 5 },
};

export type Motion = { f: number; speed: number; accel: number };

/** How fast and how hard [path] moves the screen's visible points from frame [from] to [until], frame by frame. */
export function motionOf(framing: Framing, take: TakeId, path: Path, from: number, until: number): Motion[] {
  const { width, height } = FRAME[framing];
  const inside = ([x, y]: Point) => x >= 0 && x <= width && y >= 0 && y <= height;
  const at = (f: number) => {
    const cam = cameraAt(path, f);
    const s = screenOf(framing, take, cam);
    return PROBES.map(([u, v]) => project(framing, cam, [s.left + u * s.width, s.top + v * s.height]));
  };
  const out: Motion[] = [];
  let before = at(from);
  let velocity: Point[] | null = null;
  for (let f = from + 1; f < until; f++) {
    const now = at(f);
    const cut = path.some((s) => s.from === f);
    const v = now.map((p, i) => [p[0] - before[i]![0], p[1] - before[i]![1]] as Point);
    let speed = 0;
    let accel = 0;
    if (!cut) {
      now.forEach((p, i) => {
        if (!inside(p) || !inside(before[i]!)) return;
        speed = Math.max(speed, Math.hypot(...v[i]!));
        if (velocity) accel = Math.max(accel, Math.hypot(v[i]![0] - velocity[i]![0], v[i]![1] - velocity[i]![1]));
      });
    }
    out.push({ f, speed, accel });
    before = now;
    velocity = cut ? null : v;
  }
  return out;
}

/** The frames in [frames] as runs, "12–15, 40". */
function runsOf(frames: number[]): string {
  const runs: [number, number][] = [];
  for (const f of frames) {
    const last = runs[runs.length - 1];
    if (last && f === last[1] + 1) last[1] = f;
    else runs.push([f, f]);
  }
  return runs.map(([a, b]) => (a === b ? `${a}` : `${a}–${b}`)).join(", ");
}

/**
 * What is wrong with [path]: the phone within [CLEAR] of a headline's ink while it shows, the camera's point out of the
 * frame once the phone is in, or the camera outpacing [PACE] once the phone has come in.
 */
function problemsOf(reel: Reel, framing: Framing, path: Path): string[] {
  const problems: string[] = [];
  const landed = path[0]!.curves.zoom.t[1] ?? AT.organize;
  const near: number[] = [];
  const away: number[] = [];
  for (const moment of MOMENTS) {
    const text = moment[framing].join(" ");
    const ink = INK[framing][text];
    if (!ink) throw new Error(`No ink measured for the ${framing} headline "${text}"`);
    const c = CLEAR[framing];
    const box: Point[] = [
      [ink[0] - c, ink[1] - c],
      [ink[2] + c, ink[1] - c],
      [ink[2] + c, ink[3] + c],
      [ink[0] - c, ink[3] + c],
    ];
    for (let f = moment.at; f < moment.until; f++) {
      const cam = cameraAt(path, f);
      if (overlaps(outlineOf(framing, reel.take, cam), box)) near.push(f);
      if (f >= landed && (cam.x < 0.05 || cam.x > 0.95 || cam.y < 0.05 || cam.y > 0.95)) away.push(f);
    }
  }
  if (near.length) problems.push(`the phone comes within ${CLEAR[framing]}px of the headline at ${runsOf(near)}`);
  if (away.length) problems.push(`the camera looks out of the frame at ${runsOf(away)}`);
  const motion = motionOf(framing, reel.take, path, landed, AT.lineup);
  const fast = motion.filter((m) => m.speed > PACE[framing].speed).map((m) => m.f);
  const hard = motion.filter((m) => m.accel > PACE[framing].accel).map((m) => m.f);
  if (fast.length) problems.push(`the camera moves over ${PACE[framing].speed}px a frame at ${runsOf(fast)}`);
  if (hard.length) problems.push(`the camera's pace changes by over ${PACE[framing].accel}px a frame at ${runsOf(hard)}`);
  return problems.map((p) => `${reel.take} ${framing}: ${p}`);
}

const key = (at: number, zoom: number, fx: number, fy: number, x: number, y: number, turn: number, tilt: number, roll: number): Key => ({
  at,
  zoom,
  fx,
  fy,
  x,
  y,
  turn,
  tilt,
  roll,
});

/**
 * The camera through [reel], from where it shows what each move is waiting on, in five shots cut on the beat. The phone
 * swoops in turned and tilted, pushes in on the held Project and its menu and follows it across the grid. Cut close on
 * the composer: in on the microphone, along the listening, onto the words and the send, then up with the message as it
 * flies into the chat, settling as it lands, and back as the run starts to answer; in on the edit as it is tapped open
 * and closer as its diff lands, down it and back out to all of it, in on it again as it folds and closer on the stretch
 * as it forms, then back out to the run and the follow-up field together as the field is tapped, and in on it. Cut low
 * on the composer as the follow-up is typed, onto the queue, the steer, and up with the steer to its answer. Cut wide as
 * the shade comes down and in on the run's notification. Cut to the answer, the details and the pull request.
 */
function keysOf(reel: Reel): Record<Framing, Shot[]> {
  const v = (name: string, by = 0) => whenShown(reel, mark(reel.take, name)) + by;
  const said = (name: string, end: "from" | "to", by = 0) => whenShown(reel, stream(reel.take, name)[end]) + by;
  const step = (text: string, by = 0) => whenShown(reel, liveStep(reel.take, text).from) + by;
  const moving = (keys: Key[]): Shot => ({ keys, enter: 1, leave: 1 });
  const entrance = (keys: Key[]): Shot => ({ keys, enter: 3, leave: 1 });
  return {
    wide: [
      entrance([
        key(AT.organize, 1.5, 0.5, 0.5, 0.7, 1.62, -30, 26, -5),
        key(AT.organize + 32, 1.62, 0.62, 0.62, 0.7, 0.54, -16, 8, -1.5),
        key(v("the menu", 12), 2.15, 0.7, 0.64, 0.75, 0.55, -12, 5, -0.5),
        key(v("the lift", -4), 2.25, 0.7, 0.66, 0.75, 0.56, -10, 4, 0),
        key(v("the lift", 22), 2.0, 0.54, 0.62, 0.7, 0.56, -8, 3, 0.5),
        key(v("dropped", -2), 1.85, 0.38, 0.56, 0.63, 0.55, -7, 3, 1),
        key(AT.dictate, 1.82, 0.36, 0.55, 0.62, 0.55, -6.5, 3, 1.1),
      ]),
      moving([
        key(AT.dictate, 1.75, 0.6, 0.45, 0.72, 0.52, -24, -6, 1),
        key(v("mic", 5), 2.55, 0.82, 0.42, 0.875, 0.5, -12, -2, 0),
        key(v("mic", 30), 2.7, 0.7, 0.42, 0.855, 0.5, -8, 1, -0.5),
        key(v("stop", -20), 2.0, 0.5, 0.42, 0.72, 0.5, -22, 5, 1.2),
        key(v("the words", -18), 2.35, 0.4, 0.37, 0.7, 0.5, -15, 2, 0.5),
        key(v("the words", 6), 2.75, 0.38, 0.355, 0.72, 0.5, -10, 1, 0),
        key(v("send", -3), 2.6, 0.6, 0.375, 0.775, 0.52, -11, 0, 0),
        key(v("the chat", -7), 2.6, 0.602, 0.37, 0.775, 0.52, -11, 0, 0),
        key(v("the chat", -2), 2.53, 0.611, 0.346, 0.776, 0.509, -11.3, 0.3, 0.1),
        key(v("the chat", 3), 2.4, 0.629, 0.293, 0.778, 0.487, -11.8, 0.9, 0.1),
        key(v("the chat", 8), 2.3, 0.654, 0.223, 0.78, 0.46, -12.5, 1.7, 0.2),
        key(v("the chat", 13), 2.29, 0.674, 0.165, 0.782, 0.433, -13.2, 2.5, 0.4),
        key(v("the chat", 19), 2.4, 0.686, 0.131, 0.784, 0.408, -13.8, 3.2, 0.5),
        key(v("the chat", 27), 2.58, 0.69, 0.121, 0.785, 0.4, -14, 3.4, 0.5),
        key(v("the chat", 50), 2.72, 0.66, 0.13, 0.78, 0.4, -13.5, 3.5, 0.4),
        key(said("hero.intro", "from", 4), 2.3, 0.5, 0.17, 0.7, 0.41, -11, 3, 0),
        key(v("the edit"), 2.45, 0.4, 0.2, 0.68, 0.43, -9, 2.5, -0.3),
        key(v("edit", -3), 2.75, 0.3, 0.235, 0.65, 0.45, -8, 2, -0.5),
        key(v("edit", 12), 2.95, 0.34, 0.27, 0.66, 0.44, -5, 1.5, 0),
        key(v("the diff", -1), 3.15, 0.3, 0.32, 0.66, 0.42, -2, 1, 0.4),
        key(v("the diff", 26), 2.7, 0.3, 0.45, 0.66, 0.48, -8, 2.5, 0.6),
        key(v("the diff", 56), 1.85, 0.42, 0.41, 0.68, 0.5, -14, 5, 1),
        key(v("fold", -2), 2.55, 0.35, 0.29, 0.66, 0.46, -18, 4, 0.2),
        key(v("fold", 22), 2.75, 0.355, 0.235, 0.68, 0.45, -12, 3, 0),
        key(v("the stretch", 4), 3.0, 0.36, 0.225, 0.7, 0.45, -6, 1.5, -0.4),
        key(v("follow-up", -4), 1.45, 0.5, 0.57, 0.7, 0.5, -15.5, -2.3, 0.6),
        key(AT.steer, 1.95, 0.55, 0.77, 0.7, 0.47, -18, -4, 0.8),
      ]),
      moving([
        key(AT.steer, 2.9, 0.2, 0.89, 0.6, 0.55, -18, -6, -1),
        key(AT.steer + 60, 3.0, 0.38, 0.89, 0.68, 0.55, -14, -5, -0.6),
        key(v("queue", -8), 2.8, 0.6, 0.9, 0.78, 0.56, -11, -3, 0),
        key(v("queue", 22), 2.4, 0.62, 0.835, 0.77, 0.55, -15, -2, 0.5),
        key(v("steer", -2), 2.6, 0.78, 0.83, 0.85, 0.55, -10, -1, 0),
        key(v("the steer"), 2.35, 0.6, 0.83, 0.78, 0.56, -13, 0, 0.5),
        key(v("the steer's message", 2), 1.8, 0.5, 0.7, 0.74, 0.55, -16, 3, 1),
        key(said("hero.adapt", "from", 13), 1.9, 0.56, 0.33, 0.72, 0.47, -15, 4, 0.8),
        key(AT.live, 2.4, 0.46, 0.32, 0.7, 0.48, -9, 2, -0.5),
      ]),
      moving([
        key(AT.live, 1.35, 0.5, 0.32, 0.7, 0.44, -22, 9, 1.5),
        key(SHADE.pull.at + SHADE.pull.frames + 6, 1.9, 0.5, 0.15, 0.7, 0.4, -14, 6, 0.5),
        key(step("Running gh pr create --fill"), 2.2, 0.52, 0.14, 0.71, 0.4, -10, 4, 0),
        key(SHADE.fling.at - 4, 2.5, 0.56, 0.15, 0.72, 0.42, -6, 2, -0.5),
        key(AT.ship, 2.45, 0.55, 0.17, 0.72, 0.43, -6, 2, -0.5),
      ]),
      moving([
        key(AT.ship, 1.8, 0.5, 0.42, 0.7, 0.52, -15, 3, 0.5),
        key(v("details", -30), 2.3, 0.46, 0.41, 0.68, 0.52, -10, 2, 0),
        key(v("details", -2), 1.65, 0.66, 0.27, 0.72, 0.44, -16, 5, 0.5),
        key(v("details", 28), 1.5, 0.56, 0.5, 0.7, 0.52, -18, 5, 1),
        key(v("pull request", 24), 2.0, 0.56, 0.78, 0.7, 0.55, -12, 2, 0.3),
        key(AT.lineup, 2.35, 0.56, 0.82, 0.7, 0.55, -8, 1, -0.5),
      ]),
    ],
    tall: [
      entrance([
        key(AT.organize, 1.05, 0.5, 0.5, 0.5, 1.55, -24, 28, -4),
        key(AT.organize + 32, 1.1, 0.55, 0.6, 0.52, 0.7, -12, 10, -1),
        key(v("the menu", 12), 1.35, 0.66, 0.66, 0.55, 0.83, -10, 12, -0.5),
        key(v("the lift", -4), 1.38, 0.68, 0.68, 0.55, 0.845, -9, 12, 0),
        key(v("the lift", 22), 1.25, 0.54, 0.62, 0.5, 0.775, -8, 9, 0.5),
        key(v("dropped", -2), 1.2, 0.4, 0.56, 0.46, 0.72, -6, 8, 1),
        key(AT.dictate, 1.19, 0.39, 0.555, 0.46, 0.715, -5.5, 8, 1.1),
      ]),
      moving([
        key(AT.dictate, 1.45, 0.6, 0.45, 0.5, 0.72, 8, 12, -1),
        key(v("mic", 6), 2.0, 0.8, 0.42, 0.56, 0.8, -6, 12, 0),
        key(v("mic", 30), 2.1, 0.68, 0.42, 0.54, 0.82, -10, 13, -0.5),
        key(v("stop", -20), 1.6, 0.5, 0.42, 0.5, 0.74, -18, 10, 1),
        key(v("the words", -18), 1.9, 0.38, 0.37, 0.5, 0.74, -12, 10, 0.5),
        key(v("the words", 5), 2.15, 0.4, 0.355, 0.52, 0.77, -8, 10, 0),
        key(v("send", -3), 2.05, 0.6, 0.375, 0.54, 0.76, -9, 9, 0),
        key(v("the chat", -7), 2.05, 0.602, 0.37, 0.54, 0.754, -9, 9, 0),
        key(v("the chat", -2), 2.04, 0.611, 0.346, 0.541, 0.731, -9.3, 8.9, 0.1),
        key(v("the chat", 3), 2.03, 0.629, 0.293, 0.543, 0.677, -9.8, 8.7, 0.1),
        key(v("the chat", 8), 2.03, 0.654, 0.223, 0.545, 0.605, -10.5, 8.5, 0.2),
        key(v("the chat", 13), 2.08, 0.674, 0.165, 0.547, 0.547, -11.2, 8.3, 0.4),
        key(v("the chat", 19), 2.17, 0.686, 0.131, 0.549, 0.512, -11.8, 8.1, 0.5),
        key(v("the chat", 27), 2.29, 0.69, 0.121, 0.55, 0.501, -12, 8, 0.5),
        key(v("the chat", 50), 2.4, 0.66, 0.13, 0.55, 0.5, -11.5, 8, 0.4),
        key(said("hero.intro", "from", 4), 1.7, 0.5, 0.17, 0.5, 0.5, -9, 7, 0),
        key(v("the edit", -4), 1.9, 0.4, 0.2, 0.5, 0.55, -7, 6.5, -0.3),
        key(v("edit", -2), 2.2, 0.32, 0.225, 0.47, 0.6, -6, 7, -0.5),
        key(v("edit", 12), 2.35, 0.36, 0.255, 0.48, 0.65, -4, 8, 0),
        key(v("the diff", -1), 2.45, 0.33, 0.29, 0.47, 0.72, -2, 9, 0.4),
        key(v("the diff", 26), 2.0, 0.36, 0.4, 0.48, 0.765, -7, 12, 0.6),
        key(v("the diff", 56), 1.3, 0.45, 0.38, 0.5, 0.66, -11, 9, 1),
        key(v("fold", -2), 1.85, 0.38, 0.3, 0.48, 0.62, -14, 7, 0.2),
        key(v("fold", 22), 2.0, 0.4, 0.235, 0.49, 0.56, -9, 6, 0),
        key(v("the stretch"), 2.2, 0.42, 0.235, 0.5, 0.585, -5, 5, -0.4),
        key(v("follow-up", -4), 1.1, 0.5, 0.55, 0.5, 0.67, -12, 8, 0.6),
        key(AT.steer, 1.15, 0.52, 0.6, 0.5, 0.72, -14, 9, 0.8),
      ]),
      moving([
        key(AT.steer, 1.33, 0.4, 0.88, 0.5, 0.915, 12, 18, 1),
        key(AT.steer + 60, 1.33, 0.5, 0.88, 0.5, 0.915, 6, 17, 0.5),
        key(v("queue", -8), 1.3, 0.64, 0.89, 0.52, 0.915, 0, 16, 0),
        key(v("queue", 22), 1.2, 0.6, 0.83, 0.52, 0.86, -5, 12, -0.5),
        key(v("steer", -2), 1.22, 0.75, 0.83, 0.56, 0.87, -9, 13, -0.5),
        key(v("the steer"), 1.12, 0.6, 0.8, 0.52, 0.84, -10, 10, 0),
        key(v("the steer's message", 2), 1.0, 0.55, 0.65, 0.5, 0.74, -12, 9, 0.5),
        key(said("hero.adapt", "from", 13), 1.3, 0.56, 0.32, 0.5, 0.55, -11, 7, 0.5),
        key(AT.live, 1.6, 0.46, 0.32, 0.5, 0.62, -7, 6, -0.5),
      ]),
      moving([
        key(AT.live, 1.0, 0.5, 0.35, 0.5, 0.53, -16, 10, 1.5),
        key(SHADE.pull.at + SHADE.pull.frames + 6, 1.6, 0.5, 0.15, 0.5, 0.42, -10, 6, 0.5),
        key(step("Running gh pr create --fill"), 1.85, 0.52, 0.14, 0.5, 0.43, -8, 5, 0),
        key(SHADE.fling.at - 4, 2.1, 0.55, 0.15, 0.5, 0.45, -5, 4, -0.5),
        key(AT.ship, 2.05, 0.54, 0.17, 0.5, 0.47, -5, 4, -0.5),
      ]),
      moving([
        key(AT.ship, 1.4, 0.5, 0.42, 0.5, 0.62, 10, 8, -1),
        key(v("details", -30), 1.75, 0.46, 0.41, 0.5, 0.72, 6, 7, 0),
        key(v("details", -2), 1.2, 0.68, 0.27, 0.55, 0.46, -6, 8, 0.5),
        key(v("details", 28), 1.05, 0.56, 0.55, 0.5, 0.63, -10, 8, 1),
        key(v("pull request", 24), 1.12, 0.56, 0.78, 0.5, 0.8, -7, 10, 0.5),
        key(AT.lineup, 1.18, 0.56, 0.82, 0.5, 0.84, -4, 10, -0.5),
      ]),
    ],
  };
}

function pathsOf(reel: Reel): Record<Framing, Path> {
  const keys = keysOf(reel);
  return { wide: pathOf(keys.wide), tall: pathOf(keys.tall) };
}

export const HERO_PATHS: Record<Theme, Record<Framing, Path>> = { dark: pathsOf(HEROES.dark), light: pathsOf(HEROES.light) };
const problems = (["dark", "light"] as const).flatMap((theme) =>
  (["wide", "tall"] as const).flatMap((framing) => problemsOf(HEROES[theme], framing, HERO_PATHS[theme][framing])),
);
if (problems.length) throw new Error(`The camera:\n${problems.join("\n")}`);

const still = (take: number) => whenShown(HEROES[THEME], take);
const hero = HEROES[THEME].take;

/** The frames of the 16:9 cut kept as stills: the Android card, each moment at its peak, and the lineup. */
export const STILLS = {
  android: AT.android + 50,
  organize: still(mark(hero, "the lift") + 30),
  dictate: still(mark(hero, "the words") + 12),
  code: still(mark(hero, "the diff") + 50),
  steer: still(stream(hero, "hero.adapt").to + 20),
  live: SHADE.pull.at + 80,
  ship: still(mark(hero, "pull request") + 40),
  lineup: AT.end - 30,
};
