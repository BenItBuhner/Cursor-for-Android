import type React from "react";
import type { Framing } from "../camera";
import { AT, beat, LINEUP, lineupReel, takeFrame } from "../edit";
import { clamp01, easeInOut, lerp, progress, snap } from "../math";
import { DP_WIDTH, type TakeId } from "../takes";
import { Device, deviceMargin, screenHeight } from "./Device";
import { Headline } from "./Headline";
import { Screen } from "./Screen";

type Slot = { take: TakeId; x: number; y: number; width: number };
type Box = { left: number; top: number; right: number; bottom: number };

/**
 * Where each device stands once all three have landed, every screen at the same size a dp so they read as one app at
 * three sizes, back to front: a row standing on one line in a wide frame; in a tall one, the foldable and the phone
 * standing in front of the tablet.
 */
function slots(framing: Framing, width: number, height: number): Slot[] {
  const size = (take: TakeId, dpPx: number) => {
    const w = DP_WIDTH[take] * dpPx;
    return { w, h: screenHeight(take, w), m: deviceMargin(take, w) };
  };
  if (framing === "wide") {
    const dpPx = 0.6;
    const gap = 64;
    const floor = height * 0.9;
    const sizes = LINEUP.map((take) => size(take, dpPx));
    let left = (width - sizes.reduce((sum, s) => sum + s.w + 2 * s.m, 0) - gap * (LINEUP.length - 1)) / 2;
    return LINEUP.map((take, i) => {
      const s = sizes[i]!;
      const slot = { take, x: left + s.m, y: floor - s.h, width: s.w };
      left += s.w + 2 * s.m + gap;
      return slot;
    });
  }
  const dpPx = 0.74;
  const gap = 40;
  /** How far the phone's top stands over the tablet's bottom. */
  const overlap = 150;
  const tablet = size("tablet", dpPx);
  const foldable = size("foldable", dpPx);
  const phone = size("phone", dpPx);
  const top = height * 0.28;
  const floor = top + tablet.h + 2 * tablet.m - overlap + phone.h + phone.m;
  const row = foldable.w + 2 * foldable.m + gap + phone.w + 2 * phone.m;
  const rowLeft = (width - row) / 2;
  return [
    { take: "tablet", x: (width - tablet.w) / 2, y: top + tablet.m, width: tablet.w },
    { take: "foldable", x: rowLeft + foldable.m, y: floor - foldable.h, width: foldable.w },
    { take: "phone", x: rowLeft + foldable.w + 2 * foldable.m + gap + phone.m, y: floor - phone.h, width: phone.w },
  ];
}

/** The box around [slots], glass and all. */
function boxOf(slots: Slot[]): Box {
  const box = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
  for (const s of slots) {
    const m = deviceMargin(s.take, s.width);
    box.left = Math.min(box.left, s.x - m);
    box.top = Math.min(box.top, s.y - m);
    box.right = Math.max(box.right, s.x + s.width + m);
    box.bottom = Math.max(box.bottom, s.y + screenHeight(s.take, s.width) + m);
  }
  return box;
}

/**
 * Where the camera frames what has landed before the last device does: fractions of the frame to fit it inside, under
 * the headline, and the closest it comes.
 */
const ROOM: Record<Framing, { top: number; bottom: number; side: number; zoom: number }> = {
  wide: { top: 0.25, bottom: 0.96, side: 0.08, zoom: 1.4 },
  tall: { top: 0.27, bottom: 0.97, side: 0.06, zoom: 1.9 },
};

/** The camera on the lineup: its point [cx, cy] as it stands, shown at [x, y] of the frame at [zoom] times its size. */
type View = { zoom: number; cx: number; cy: number; x: number; y: number };

/**
 * The view once the first [n] devices to land have: in on those, centred in the room at the size that fits them there
 * (up to [maxZoom]); or, once they would fit no larger than they stand, as all three do, the lineup as it stands.
 */
function shot(all: Slot[], n: number, room: Box, maxZoom: number): View {
  const box = boxOf(all.filter((s) => LINEUP.indexOf(s.take) < n));
  const cx = (box.left + box.right) / 2;
  const cy = (box.top + box.bottom) / 2;
  const fit = Math.min((room.right - room.left) / (box.right - box.left), (room.bottom - room.top) / (box.bottom - box.top));
  if (n >= LINEUP.length || fit <= 1) return { zoom: 1, cx, cy, x: cx, y: cy };
  return { zoom: Math.min(maxZoom, fit), cx, cy, x: (room.left + room.right) / 2, y: (room.top + room.bottom) / 2 };
}

const landAt = (i: number) => AT.lineup + beat(i);

/** Frames a device takes to land, and the camera to reframe as it does, starting a little before. */
const LAND = 18;
const REFRAME = 30;
const LEAD = 12;

/** The view at frame [f]: reframed around each device as it lands, from the shot before. */
function viewAt(framing: Framing, all: Slot[], width: number, height: number, f: number): View {
  const r = ROOM[framing];
  const room = { left: width * r.side, right: width * (1 - r.side), top: height * r.top, bottom: height * r.bottom };
  let view = shot(all, 1, room, r.zoom);
  for (let n = 2; n <= LINEUP.length; n++) {
    const to = shot(all, n, room, r.zoom);
    const p = easeInOut(progress(f, landAt(n - 1) - LEAD, landAt(n - 1) - LEAD + REFRAME));
    view = {
      zoom: lerp(view.zoom, to.zoom, p),
      cx: lerp(view.cx, to.cx, p),
      cy: lerp(view.cy, to.cy, p),
      x: lerp(view.x, to.x, p),
      y: lerp(view.y, to.y, p),
    };
  }
  return view;
}

/**
 * The three devices landing one a beat, each named as it lands, all playing the same moment of the run in step: the
 * camera in on the phone, pulling back as the foldable and the tablet land beside it.
 */
export const Lineup: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const push = 1 + 0.03 * progress(f, AT.lineup, AT.end);
  const size = framing === "wide" ? 104 : 112;
  const lines = framing === "wide" ? ["Phone. Foldable. Tablet."] : ["Phone.", "Foldable.", "Tablet."];
  const all = slots(framing, width, height);
  const view = viewAt(framing, all, width, height, f);
  return (
    <div style={{ position: "absolute", inset: 0, transform: `scale(${push})` }}>
      {all.map((slot) => {
        const i = LINEUP.indexOf(slot.take);
        const landed = snap(progress(f, landAt(i), landAt(i) + LAND));
        const x = view.x + (slot.x - view.cx) * view.zoom;
        const y = view.y + (slot.y - view.cy) * view.zoom;
        const w = slot.width * view.zoom;
        return (
          <div
            key={slot.take}
            style={{
              position: "absolute",
              inset: 0,
              opacity: clamp01((f - landAt(i) + 1) / 3),
              transform: `translateY(${(1 - landed) * 70}px) scale(${0.94 + 0.06 * landed})`,
              transformOrigin: `${x + w / 2}px ${y + screenHeight(slot.take, w)}px`,
            }}
          >
            <Device take={slot.take} x={x} y={y} width={w}>
              <Screen take={slot.take} frame={takeFrame(lineupReel(slot.take), f)} width={w} />
            </Device>
          </div>
        );
      })}
      <Headline
        lines={lines}
        f={f}
        at={AT.lineup}
        size={size}
        x={0}
        y={framing === "wide" ? height * 0.11 : height * 0.075}
        align="center"
        wordAt={LINEUP.map((_, i) => landAt(i))}
      />
    </div>
  );
};
