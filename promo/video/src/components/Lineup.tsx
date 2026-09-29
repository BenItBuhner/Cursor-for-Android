import type React from "react";
import type { Framing } from "../camera";
import { AT, beat, LINEUP, lineupReel, takeFrame } from "../edit";
import { clamp01, progress, snap } from "../math";
import { DP_WIDTH, type TakeId } from "../takes";
import { Device, deviceMargin, screenHeight } from "./Device";
import { Headline } from "./Headline";
import { Screen } from "./Screen";

type Slot = { take: TakeId; x: number; y: number; width: number };

/**
 * Where each device stands, every screen at the same size a dp so they read as one app at three sizes: a row standing
 * on one line in a wide frame; in a tall one, the tablet over the foldable and the phone.
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
  const dpPx = 0.66;
  const gap = 56;
  const tablet = size("tablet", dpPx);
  const foldable = size("foldable", dpPx);
  const phone = size("phone", dpPx);
  const top = height * 0.3;
  const floor = top + tablet.h + 2 * tablet.m + gap + phone.h + phone.m;
  const row = foldable.w + 2 * foldable.m + gap + phone.w + 2 * phone.m;
  const rowLeft = (width - row) / 2;
  return [
    { take: "phone", x: rowLeft + foldable.w + 2 * foldable.m + gap + phone.m, y: floor - phone.h, width: phone.w },
    { take: "foldable", x: rowLeft + foldable.m, y: floor - foldable.h, width: foldable.w },
    { take: "tablet", x: (width - tablet.w) / 2, y: top + tablet.m, width: tablet.w },
  ];
}

/** Frames a device takes to land. */
const LAND = 18;

/** The three devices landing one a beat, each named as it lands, all playing the same moment of the run in step. */
export const Lineup: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const push = 1 + 0.03 * progress(f, AT.lineup, AT.end);
  const landAt = (i: number) => AT.lineup + beat(i);
  const size = framing === "wide" ? 104 : 112;
  const lines = framing === "wide" ? ["Phone. Foldable. Tablet."] : ["Phone.", "Foldable.", "Tablet."];
  return (
    <div style={{ position: "absolute", inset: 0, transform: `scale(${push})` }}>
      {slots(framing, width, height).map((slot) => {
        const i = LINEUP.indexOf(slot.take);
        const landed = snap(progress(f, landAt(i), landAt(i) + LAND));
        return (
          <div
            key={slot.take}
            style={{
              position: "absolute",
              inset: 0,
              opacity: clamp01((f - landAt(i) + 1) / 3),
              transform: `translateY(${(1 - landed) * 70}px) scale(${0.94 + 0.06 * landed})`,
              transformOrigin: `${slot.x + slot.width / 2}px ${slot.y + screenHeight(slot.take, slot.width)}px`,
            }}
          >
            <Device take={slot.take} x={slot.x} y={slot.y} width={slot.width}>
              <Screen take={slot.take} frame={takeFrame(lineupReel(slot.take), f)} width={slot.width} />
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
