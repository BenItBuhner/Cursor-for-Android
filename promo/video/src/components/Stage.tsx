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
    if (p >= 0 && p < 1) return easeOut(p);
  }
  return null;
}

/** How wide the band of light is, as a fraction of the frame. */
const BAND = 0.55;

/** Where the beam down the band's middle is at [f], in pixels from the frame's left edge, or null between sweeps. */
export function beamAt(f: number, width: number): number | null {
  const sweep = sweepAt(f);
  if (sweep === null) return null;
  const band = width * BAND;
  return -band / 2 + (width + 2 * band) * sweep;
}

/**
 * A wipe carried by the beam of the sweep under way at [f]: masks for the layer going out and the layer coming in. The
 * one going out fades down into the stage as the beam reaches it and is gone once the beam has passed; the one coming
 * in comes up out of the stage a little behind the beam; both with wide feathers, so the light reads as carrying the
 * change across the frame rather than as an edge. Null once the beam has carried it the whole way across.
 */
export function wipeAt(f: number, width: number): { out: string; in: string } | null {
  const beam = beamAt(f, width);
  if (beam === null) return null;
  const gone = beam - width * 0.08;
  const inBy = beam - width * 0.45;
  if (inBy >= width) return null;
  const px = (v: number) => `${v.toFixed(0)}px`;
  return {
    out: `linear-gradient(90deg, rgba(0,0,0,0) ${px(gone)}, #000 ${px(beam + width * 0.3)})`,
    in: `linear-gradient(90deg, #000 ${px(inBy)}, rgba(0,0,0,0) ${px(gone)})`,
  };
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
  const band = width * BAND;
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
            // A soft wash with a narrow, brighter core down its middle, so the light reads as a beam rather than a fog.
            background: [
              "linear-gradient(90deg, rgba(255,243,226,0) 42%, rgba(255,243,226,0.07) 47%, rgba(255,243,226,0.12) 50%, rgba(255,243,226,0.07) 53%, rgba(255,243,226,0) 58%)",
              "linear-gradient(90deg, rgba(255,243,226,0) 0%, rgba(255,243,226,0.06) 35%, rgba(255,243,226,0.12) 50%, rgba(255,243,226,0.06) 65%, rgba(255,243,226,0) 100%)",
            ].join(", "),
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