import type React from "react";
import { takes, type TakeId } from "../takes";

/** A device's glass around its screen, as fractions of the screen's shorter side. */
const GLASS: Record<TakeId, { bezel: number; radius: number }> = {
  phone: { bezel: 0.021, radius: 0.105 },
  foldable: { bezel: 0.017, radius: 0.05 },
  tablet: { bezel: 0.034, radius: 0.05 },
};

/** The screen's height for [width] pixels across, as the take has it. */
export const screenHeight = (take: TakeId, width: number) => (width * takes[take].height) / takes[take].width;

/** How far the device reaches past its screen on each side, in pixels, for a screen [width] across. */
export function deviceMargin(take: TakeId, width: number): number {
  const short = Math.min(width, screenHeight(take, width));
  return short * GLASS[take].bezel + rimOf(short);
}

const rimOf = (short: number) => Math.max(1.5, short * 0.0045);

/**
 * [take]'s device, its screen [width] pixels across with its top left at [x, y] of the parent: thin black glass in a
 * graphite frame, the camera where that device has it, and [children] (the screen) inside.
 */
export const Device: React.FC<{ take: TakeId; x: number; y: number; width: number; children: React.ReactNode }> = ({
  take,
  x,
  y,
  width,
  children,
}) => {
  const height = screenHeight(take, width);
  const short = Math.min(width, height);
  const bezel = short * GLASS[take].bezel;
  const radius = short * GLASS[take].radius;
  const rim = rimOf(short);
  const edge = bezel + rim;
  return (
    <div
      style={{
        position: "absolute",
        left: x - edge,
        top: y - edge,
        width: width + 2 * edge,
        height: height + 2 * edge,
        borderRadius: radius + edge,
        background: "linear-gradient(150deg, #6d6d74 0%, #26262b 18%, #1a1a1e 55%, #2e2e33 82%, #77777e 100%)",
        boxShadow: `0 ${short * 0.06}px ${short * 0.14}px -${short * 0.03}px rgba(20,18,11,0.42), 0 ${short * 0.012}px ${short * 0.03}px rgba(20,18,11,0.2)`,
      }}
    >
      {take === "phone" ? <PhoneButtons width={width} height={height} edge={edge} rim={rim} /> : null}
      <div style={{ position: "absolute", inset: rim, borderRadius: radius + bezel, background: "#050506" }} />
      <div style={{ position: "absolute", left: edge, top: edge, width, height, borderRadius: radius, overflow: "hidden", isolation: "isolate" }}>
        {children}
        {take === "foldable" ? <Crease width={width} height={height} /> : null}
        {take !== "tablet" ? <PunchHole take={take} width={width} height={height} /> : null}
      </div>
      {take === "tablet" ? (
        <div
          style={{
            position: "absolute",
            left: edge + width / 2 - short * 0.007,
            top: rim + bezel / 2 - short * 0.007,
            width: short * 0.014,
            height: short * 0.014,
            borderRadius: "50%",
            background: "radial-gradient(circle at 35% 35%, #2a3140 0%, #0b0d12 55%, #000 100%)",
          }}
        />
      ) : null}
    </div>
  );
};

/** The front camera's punch hole, centred in the status bar: over the phone's middle, and the foldable's right half. */
const PunchHole: React.FC<{ take: TakeId; width: number; height: number }> = ({ take, width, height }) => {
  const t = takes[take];
  const scale = width / t.width;
  const d = Math.min(width, height) * (take === "phone" ? 0.028 : 0.019);
  const cx = take === "phone" ? width / 2 : width * 0.8;
  const cy = ((t.statusBar[0] ?? 0) * scale) / 2;
  return (
    <div
      style={{
        position: "absolute",
        left: cx - d / 2,
        top: cy - d / 2,
        width: d,
        height: d,
        borderRadius: "50%",
        background: "radial-gradient(circle at 36% 34%, #262c3a 0%, #07080b 50%, #000 72%)",
        boxShadow: `0 0 0 ${d * 0.08}px #000`,
      }}
    />
  );
};

/** Where the foldable's inner screen bends: a shadow and a highlight a few pixels wide down its middle. */
const Crease: React.FC<{ width: number; height: number }> = ({ width, height }) => {
  const w = Math.max(4, width * 0.012);
  return (
    <div
      style={{
        position: "absolute",
        left: width / 2 - w / 2,
        top: 0,
        width: w,
        height,
        background: "linear-gradient(90deg, rgba(255,255,255,0) 0%, rgba(255,255,255,0.035) 30%, rgba(0,0,0,0.16) 55%, rgba(255,255,255,0.025) 75%, rgba(255,255,255,0) 100%)",
        pointerEvents: "none",
      }}
    />
  );
};

/** The power key and the volume rocker on the phone's right edge, standing a hair proud of the frame. */
const PhoneButtons: React.FC<{ width: number; height: number; edge: number; rim: number }> = ({ width, height, edge, rim }) => {
  const depth = Math.max(1.5, rim * 1.1);
  const key = (top: number, length: number) => (
    <div
      style={{
        position: "absolute",
        left: width + 2 * edge - rim * 0.3,
        top: edge + height * top,
        width: depth,
        height: height * length,
        borderRadius: `0 ${depth}px ${depth}px 0`,
        background: "linear-gradient(90deg, #3a3a40, #6a6a71)",
      }}
    />
  );
  return (
    <>
      {key(0.2, 0.065)}
      {key(0.31, 0.12)}
    </>
  );
};
