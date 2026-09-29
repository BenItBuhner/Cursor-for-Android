// Brings the capture's takes (promo/capture/out) into the video: each take's MP4 copied to public/footage, and
// public/footage/takes.json with what the capture recorded beside every frame (the app's content size, the system
// bars' heights, the clock, a finger, the take's marks) and the frames each streamed text and edit runs over, which
// the edit may cut between but never plays faster than the capture.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(video, "..", "capture", "out");
const footage = join(video, "public", "footage");

const TAKES = ["phone", "foldable", "tablet"];

/** The repository coalesces a burst for up to 80 ms before the screen shows it: text lands a few frames on. */
const LANDING_FRAMES = 8;

mkdirSync(footage, { recursive: true });
const takes = {};
for (const id of TAKES) {
  const mp4 = join(out, id, `${id}.mp4`);
  const jsonl = join(out, id, `${id}.jsonl`);
  if (!existsSync(mp4) || !existsSync(jsonl)) throw new Error(`No ${id} take: run promo/capture/run.sh ${id}`);
  // The capture's frames reach ffmpeg as BGRA, which its scaler converts with BT.601's matrix and leaves untagged, and
  // Remotion reads an untagged stream as BT.709: the UI's colours would shift a few levels. The stream says so, as is.
  const tagged = spawnSync(
    "ffmpeg",
    ["-v", "error", "-y", "-i", mp4, "-c", "copy", "-bsf:v", "h264_metadata=matrix_coefficients=6:video_full_range_flag=0", join(footage, `${id}.mp4`)],
    { stdio: "inherit" },
  );
  if (tagged.status !== 0) throw new Error(`ffmpeg could not copy ${mp4}`);
  const frames = readFileSync(jsonl, "utf8").trim().split("\n").map((line) => JSON.parse(line));
  const marks = {};
  frames.forEach((f, i) => (f.marks ?? []).forEach((m) => (marks[m] = i)));
  if (marks.send === undefined) throw new Error(`The ${id} take has no send`);
  const pacing = JSON.parse(readFileSync(join(out, id, "pacing.json"), "utf8"));
  const t0 = frames[0].t;
  const t1 = frames[frames.length - 1].t;
  const byKey = new Map();
  for (const burst of pacing.bursts) {
    if (burst.t < t0 || burst.t > t1) continue;
    const frame = frames.findIndex((f) => f.t >= burst.t);
    const span = byKey.get(burst.key) ?? { key: burst.key, kind: burst.kind, from: frame, to: frame, tokens: 0 };
    span.to = Math.min(frames.length - 1, frame + LANDING_FRAMES);
    span.tokens += burst.tokens;
    byKey.set(burst.key, span);
  }
  const width = even(Math.max(...frames.map((f) => f.content[2])));
  const height = even(Math.max(...frames.map((f) => f.content[3])));
  takes[id] = {
    file: `footage/${id}.mp4`,
    width,
    height,
    frames: frames.length,
    frameMs: (t1 - t0) / (frames.length - 1),
    t: frames.map((f) => f.t),
    clock: frames.map((f) => f.clock),
    statusBar: frames.map((f) => f.statusBar),
    navBar: frames.map((f) => f.navBar),
    touch: frames.map((f) => (f.touch ? [f.touch.x, f.touch.y, f.touch.down ? 1 : 0] : null)),
    marks,
    streams: [...byKey.values()],
    peakPerSecond: pacing.peakPerSecond,
  };
  console.log(`${id}: ${frames.length} frames ${width}x${height}, peak ${pacing.peakPerSecond}/s, ${byKey.size} streams`);
  console.log(`  marks ${JSON.stringify(marks)}`);
}
writeFileSync(join(footage, "takes.json"), JSON.stringify(takes));

function even(v) {
  return v - (v % 2);
}
