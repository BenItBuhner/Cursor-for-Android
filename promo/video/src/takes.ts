import raw from "../public/footage/takes.json";

export type TakeId = "phone" | "foldable" | "tablet";

/** Text or an edit the scripted run streamed in a take: the frames from its first token to its landing on screen. */
export type Stream = { key: string; kind: "thinking" | "assistant" | "edit"; from: number; to: number; tokens: number };

/** A take as `scripts/footage.mjs` wrote it: the MP4 and what the capture recorded beside each of its frames. */
export type Take = {
  file: string;
  width: number;
  height: number;
  frames: number;
  /** Virtual milliseconds from one frame to the next: the capture's clock, not the video's. */
  frameMs: number;
  t: number[];
  clock: string[];
  statusBar: number[];
  navBar: number[];
  /** A finger on the screen: x, y and 1 while it is down, 0 in the frames after it lifts. */
  touch: ([number, number, number] | null)[];
  marks: Record<string, number>;
  streams: Stream[];
  peakPerSecond: number;
};

export const takes = raw as unknown as Record<TakeId, Take>;

/** The take's frame [ms] after the prompt was sent: every take runs the same script from there. */
export const afterSend = (take: TakeId, ms: number) => takes[take].marks.send! + Math.round(ms / takes[take].frameMs);

export function mark(take: TakeId, name: string): number {
  const frame = takes[take].marks[name];
  if (frame === undefined) throw new Error(`The ${take} take has no mark "${name}"`);
  return frame;
}

export function stream(take: TakeId, key: string): Stream {
  const s = takes[take].streams.find((x) => x.key === key);
  if (!s) throw new Error(`The ${take} take streams no ${key}`);
  return s;
}

/** Screen pixels to a dp on the take's screen: the capture filmed each at its own density and scale. */
export const DP_WIDTH: Record<TakeId, number> = { phone: 411, foldable: 791, tablet: 1280 };
export const pxPerDp = (take: TakeId) => takes[take].width / DP_WIDTH[take];
