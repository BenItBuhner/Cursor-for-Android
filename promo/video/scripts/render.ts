// Renders the launch video from the capture's takes: the cues and the score, the 16:9 cut, the 9:16 cut and the stills,
// into out/, and copies them to --dest if given.
//
//   npx tsx scripts/render.ts [--only=wide,tall,stills] [--dest=DIR] [--concurrency=3]
//
// Needs the takes (promo/capture/run.sh phone, foldable and tablet, then `npm run footage`), and python3 with
// scripts/requirements.txt for the score.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const args: Record<string, string> = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k!, v ?? "true"];
  }),
);
const only = new Set((args.only ?? "wide,tall,stills").split(","));
const concurrency = args.concurrency ?? "3";
const out = join(video, "out");

const NAME = "cursor-for-android-launch";
const CUTS = [
  { key: "wide", composition: "Launch", framing: "wide", file: `${NAME}.mp4` },
  { key: "tall", composition: "LaunchVertical", framing: "tall", file: `${NAME}-vertical.mp4` },
];

function run(command: string, argv: string[]) {
  console.log(`\n$ ${command} ${argv.join(" ")}`);
  const r = spawnSync(command, argv, { cwd: video, stdio: "inherit" });
  if (r.status !== 0) throw new Error(`${command} failed with ${r.status}`);
}

if (!existsSync(join(video, "public", "footage", "takes.json"))) run("node", ["scripts/footage.mjs"]);
// The edit reads the takes, so it is loaded only once they are there.
const { STILLS } = await import("../src/camera");

run("npx", ["tsx", "scripts/cues.ts"]);
run("python3", ["scripts/music.py", "public/audio/score.wav"]);

mkdirSync(out, { recursive: true });
const made: string[] = [];
for (const cut of CUTS) {
  if (!only.has(cut.key)) continue;
  const file = join(out, cut.file);
  const props = JSON.stringify({ framing: cut.framing, music: true });
  run("npx", ["remotion", "render", cut.composition, file, `--props=${props}`, `--concurrency=${concurrency}`]);
  made.push(file);
}
if (only.has("stills")) {
  const props = JSON.stringify({ framing: "wide", music: false });
  for (const [name, frame] of Object.entries(STILLS)) {
    const file = join(out, `${NAME}-${name}.png`);
    run("npx", ["remotion", "still", "Launch", file, `--frame=${frame}`, "--image-format=png", `--props=${props}`]);
    made.push(file);
  }
}

if (args.dest) {
  mkdirSync(args.dest, { recursive: true });
  for (const file of made) copyFileSync(file, join(args.dest, basename(file)));
  console.log(`\nCopied ${made.length} files to ${args.dest}`);
}
