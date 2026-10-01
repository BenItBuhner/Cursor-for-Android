import type React from "react";
import { SWEEPS } from "../edit";
import { easeOut, progress } from "../math";
import { COLOR } from "../theme";

/** Frames a sweep takes to cross. */
const SWEEP = 46;

/** How far across the frame the light is at [f], 0 off its left edge to 1 off its right, or null between sweeps. */
export function sweepAt(f: number): number | null {
  for (const at of SWEEPS) {
    const p = progress(f, at, at + SWEEP);
    if (p > 0 && p < 1) return easeOut(p);
  }
  return null;
}

/** A tile of fine, fixed grain, so the stage's gradients read as a surface rather than a fill. */
const GRAIN = `url("data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><filter id="g"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" seed="7"/><feColorMatrix values="0 0 0 0 1  0 0 0 0 0.97  0 0 0 0 0.92  0 0 0 0.9 0"/></filter><rect width="240" height="240" filter="url(#g)"/></svg>`,
)}")`;

/**
 * The stage: warm near-black, lit from a little above its middle and falling off to the edges, with a fixed grain over
 * it, and a soft band of light that sweeps across it on each act's first beat.
 */
export const Stage: React.FC<{ f: number; width: number; height: number }> = ({ f, width, height }) => {
  const sweep = sweepAt(f);
  const band = width * 0.55;
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.stage, overflow: "hidden" }}>
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: `radial-gradient(ellipse 62% 56% at 50% 42%, ${COLOR.stageLit} 0%, ${COLOR.stage} 100%)`,
        }}
      />
      <div style={{ position: "absolute", inset: 0, backgroundImage: GRAIN, opacity: 0.045, mixBlendMode: "screen" }} />
      {sweep !== null ? (
        <div
          style={{
            position: "absolute",
            top: -height * 0.4,
            height: height * 1.8,
            width: band,
            left: -band + (width + 2 * band) * sweep,
            transform: "skewX(-14deg)",
            background:
              "linear-gradient(90deg, rgba(255,243,226,0) 0%, rgba(255,243,226,0.06) 35%, rgba(255,243,226,0.13) 50%, rgba(255,243,226,0.06) 65%, rgba(255,243,226,0) 100%)",
          }}
        />
      ) : null}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: "radial-gradient(ellipse 90% 85% at 50% 50%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.45) 100%)",
        }}
      />
    </div>
  );
};