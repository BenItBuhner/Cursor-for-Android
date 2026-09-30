import { mark, stream, takes, type TakeId } from "./takes";

export const FPS = 60;
export const BPM = 120;
/** Frames to a beat: the score's grid, which every cut lands on. */
export const BEAT = (60 / BPM) * FPS;
export const beat = (n: number) => Math.round(n * BEAT);

/** Where each part of the video starts, on the beat. */
export const AT = {
  title: beat(0),
  wordmark: beat(1),
  android: beat(3),
  start: beat(6),
  /** The prompt is sent on this beat. */
  send: beat(10),
  code: beat(13),
  steer: beat(22),
  ship: beat(30),
  lineup: beat(36),
  end: beat(44),
};

export const DURATION = AT.end + beat(8);

/** The moments told over the phone, each with its headline. */
export const MOMENTS = [
  { at: AT.start, until: AT.code, lines: ["Launch an", "agent."] },
  { at: AT.code, until: AT.steer, lines: ["Watch it", "code."] },
  { at: AT.steer, until: AT.ship, lines: ["Steer it."] },
  { at: AT.ship, until: AT.lineup, lines: ["Ship it."] },
];

/**
 * A take played from frame [take] at frame [at] of the video, [speed] take frames to one of the video's (1 unless the
 * take is only typing or idle), until the next cut.
 */
export type Cut = { at: number; take: number; speed?: number };
export type Reel = { take: TakeId; cuts: Cut[]; until: number };

/** The take's frame on screen at frame [f] of the video. */
export function takeFrame(reel: Reel, f: number): number {
  let cut = reel.cuts[0]!;
  for (const c of reel.cuts) if (c.at <= f) cut = c;
  return Math.min(takes[reel.take].frames - 1, Math.round(cut.take + (f - cut.at) * (cut.speed ?? 1)));
}

/** The first frame of the video to show the reel's take at or past frame [take]. */
export function whenShown(reel: Reel, take: number): number {
  for (let f = reel.cuts[0]!.at; f < reel.until; f++) if (takeFrame(reel, f) >= take) return f;
  throw new Error(`${reel.take}: the reel never gets to take frame ${take}`);
}

/**
 * A stretch of a take from frame [from]: to frame [to] (or for [frames] of the video) at [speed] take frames a frame,
 * 1 by default; given all three, the speed is what fits. A part's last piece runs to the part's end.
 */
type Piece = { from: number; to?: number; frames?: number; speed?: number };
type Part = { at: number; until: number; pieces: Piece[] };

function cuts(parts: Part[]): Cut[] {
  const out: Cut[] = [];
  for (const part of parts) {
    let at = part.at;
    part.pieces.forEach((p, i) => {
      const last = i === part.pieces.length - 1;
      let speed = p.speed ?? 1;
      let frames = p.frames;
      if (frames !== undefined && p.to !== undefined) speed = (p.to - p.from) / frames;
      else if (p.to !== undefined) frames = Math.round((p.to - p.from) / speed);
      if (last && frames === undefined) frames = part.until - at;
      if (frames === undefined) throw new Error(`The piece from take ${p.from} has no length`);
      out.push(speed === 1 ? { at, take: p.from } : { at, take: p.from, speed });
      at += frames;
    });
    if (at !== part.until) throw new Error(`The part from ${part.at} ends at ${at}, not ${part.until}`);
  }
  return out;
}

const m = (name: string) => mark("phone", name);
const s = (key: string) => stream("phone", key);

/** The phone's take from here on shows the run working, its thinking folded away, until the intro starts. */
const working = s("hero.intro").from - 16;
/** Frames of the chat starting that play after the send, before the cut to the run working. */
const STARTING = 50;

/**
 * The phone's take through the four moments. The prompt is typed at a little over twice the take's pace and the
 * follow-up at three times once the last edit has landed; the quiet frames (the chat starting, the queued card
 * waiting on the agent) are cut short, and the thinking is cut out whole. Everything else the run streams plays as the
 * capture filmed it, and the cuts leave out whole stretches of the run rather than any of what it says.
 */
export const HERO: Reel = {
  take: "phone",
  until: AT.lineup,
  cuts: cuts([
    {
      at: AT.start,
      until: AT.code,
      pieces: [
        { from: m("composer") - 22, to: m("composer") + 8 },
        { from: m("composer") + 8, to: m("send") - 24, frames: AT.send - 24 - (AT.start + 30) },
        { from: m("send") - 24, to: m("send") + STARTING },
        { from: working - (AT.code - (AT.send + STARTING)), to: working },
      ],
    },
    {
      // From the intro on, through the edits landing and the first diff opening, to the follow-up typed as the last
      // edits land, queued, steered into the turn and answered.
      at: AT.code,
      until: AT.ship,
      pieces: [
        { from: working, to: s("src/components/Header.tsx").to + 3 },
        { from: s("src/components/Header.tsx").to + 3, to: m("queue") - 6, speed: 3 },
        { from: m("queue") - 6, to: m("steer") + 29 },
        { from: m("the steer") - 4 },
      ],
    },
    {
      at: AT.ship,
      until: AT.lineup,
      pieces: [
        { from: s("hero.final").from - 11, to: s("hero.final").to + 13 },
        { from: m("details") - 6, to: m("details") + 34 },
        { from: m("pull request") - 8 },
      ],
    },
  ]),
};

/**
 * Each device's take in the lineup: the same stretch, the edits landing and the first diff opening, in step. Held to
 * the capture's own steps, which every take films on the same frames; a take's stream can land a frame or two apart.
 */
export const lineupReel = (take: TakeId): Reel => ({
  take,
  until: AT.end,
  cuts: [{ at: AT.lineup, take: mark(take, "edits") - 25 }],
});

export const LINEUP: TakeId[] = ["phone", "foldable", "tablet"];

/**
 * The rule the whole cut keeps: what the run streams (its text, its thinking and the lines of its edits) is never shown
 * faster than the capture filmed it, which the capture holds to 100 tokens a second, and no cut jumps out of or into
 * the middle of one. Throws on the first cut that breaks it.
 */
export function check(reels: Reel[]) {
  for (const reel of reels) {
    const take = takes[reel.take];
    reel.cuts.forEach((cut, i) => {
      const end = reel.cuts[i + 1]?.at ?? reel.until;
      if (end <= cut.at) throw new Error(`${reel.take}: the cut at ${cut.at} is out of order`);
      const from = cut.take;
      const to = Math.round(cut.take + (end - 1 - cut.at) * (cut.speed ?? 1));
      if (to >= take.frames) throw new Error(`${reel.take}: the take runs out before frame ${end}`);
      for (const st of take.streams) {
        if ((cut.speed ?? 1) > 1 && from <= st.to && to >= st.from) {
          throw new Error(`${reel.take}: the cut at ${cut.at} plays ${st.key} (take ${st.from}–${st.to}) at ${cut.speed}x`);
        }
      }
      const next = reel.cuts[i + 1];
      if (!next || Math.abs(next.take - (to + 1)) <= 1) return;
      for (const st of take.streams) {
        const inside = (x: number) => x > st.from && x < st.to;
        if (inside(to) || inside(next.take)) {
          throw new Error(`${reel.take}: the cut at ${next.at} jumps from take ${to} to ${next.take}, inside ${st.key}`);
        }
      }
    });
  }
}
