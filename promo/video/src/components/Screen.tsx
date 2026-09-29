import type React from "react";
import { Freeze, OffthreadVideo, staticFile } from "remotion";
import { easeOut } from "../math";
import { pxPerDp, takes, type TakeId } from "../takes";
import { COLOR, SANS } from "../theme";

/**
 * Frame [frame] of [take] at [width] pixels across, with the system bars and a finger drawn over it: the capture has
 * no system UI to draw them, so the app was told their insets and left their room empty. The take is held at its
 * first frame and trimmed to [frame]: Remotion clamps a frozen frame to the composition's length, which a take's
 * later frames run past, but not a trim.
 */
export const Screen: React.FC<{ take: TakeId; frame: number; width: number }> = ({ take, frame, width }) => {
  const t = takes[take];
  const f = Math.max(0, Math.min(t.frames - 1, Math.round(frame)));
  const scale = width / t.width;
  return (
    <div style={{ position: "absolute", inset: 0, overflow: "hidden", background: "#000" }}>
      <Freeze frame={0}>
        <OffthreadVideo
          src={staticFile(t.file)}
          trimBefore={f}
          muted
          toneMapped={false}
          style={{ position: "absolute", left: 0, top: 0, width, height: t.height * scale, maxWidth: "none" }}
        />
      </Freeze>
      <div style={{ position: "absolute", left: 0, top: 0, width: t.width, height: t.height, transform: `scale(${scale})`, transformOrigin: "0 0" }}>
        <SystemBars take={take} f={f} />
        <Finger take={take} f={f} />
      </div>
    </div>
  );
};

/** The status bar (the take's clock, Wi-Fi and a full battery) and the gesture handle, in the take's pixels. */
const SystemBars: React.FC<{ take: TakeId; f: number }> = ({ take, f }) => {
  const t = takes[take];
  const dp = (v: number) => v * pxPerDp(take);
  const status = t.statusBar[f] ?? 0;
  const nav = t.navBar[f] ?? 0;
  const side = take === "phone" ? dp(24) : dp(20);
  return (
    <>
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width: t.width,
          height: status,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: `0 ${side}px`,
          boxSizing: "border-box",
          color: COLOR.statusText,
          fontFamily: SANS,
          fontWeight: 500,
          fontSize: dp(take === "phone" ? 14.5 : 13),
          letterSpacing: dp(0.1),
        }}
      >
        <span style={{ fontVariantNumeric: "tabular-nums" }}>{t.clock[f] ?? "9:41"}</span>
        <span style={{ display: "flex", alignItems: "center", gap: dp(6) }}>
          <Wifi size={dp(15)} />
          <Battery size={dp(15)} />
        </span>
      </div>
      <div
        style={{
          position: "absolute",
          left: t.width / 2 - dp(54),
          top: t.height - nav / 2 - dp(2),
          width: dp(108),
          height: dp(4),
          borderRadius: dp(2),
          background: "rgba(245,245,245,0.78)",
        }}
      />
    </>
  );
};

const Wifi: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size * 1.12} height={size} viewBox="0 0 28 25" style={{ display: "block" }}>
    <path d="M14 24.2 L0.9 8.1 C4.5 5.1 9.1 3.3 14 3.3 C18.9 3.3 23.5 5.1 27.1 8.1 Z" fill={COLOR.statusText} />
  </svg>
);

const Battery: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size * 0.56} height={size * 1.06} viewBox="0 0 14 26" style={{ display: "block" }}>
    <rect x="4.5" y="0" width="5" height="2.6" rx="1" fill={COLOR.statusText} />
    <rect x="0.9" y="2.4" width="12.2" height="22.7" rx="2.4" fill={COLOR.statusText} />
  </svg>
);

/** A press in a take: where, the frame the finger went down and the one it lifted on. */
type Press = { x: number; y: number; down: number; up: number };

/** Frames a lifted finger's mark takes to fade. */
const LIFT_FRAMES = 14;

const pressCache = new Map<TakeId, Press[]>();

function pressesOf(take: TakeId): Press[] {
  const cached = pressCache.get(take);
  if (cached) return cached;
  const out: Press[] = [];
  let wasDown = false;
  takes[take].touch.forEach((touch, i) => {
    const isDown = touch?.[2] === 1;
    if (touch && isDown && !wasDown) out.push({ x: touch[0], y: touch[1], down: i, up: Number.POSITIVE_INFINITY });
    const last = out[out.length - 1];
    if (!isDown && wasDown && last) last.up = i;
    wasDown = isDown;
  });
  pressCache.set(take, out);
  return out;
}

/** The finger, as a soft disc under it while it is down that swells and fades as it lifts, or moves on to press again. */
const Finger: React.FC<{ take: TakeId; f: number }> = ({ take, f }) => {
  let press: Press | undefined;
  for (const p of pressesOf(take)) if (f >= p.down) press = p;
  if (!press || f >= press.up + LIFT_FRAMES) return null;
  const r = 24 * pxPerDp(take);
  const landed = easeOut(Math.min(1, (f - press.down + 1) / 6));
  const lifted = f >= press.up ? (f - press.up + 1) / LIFT_FRAMES : 0;
  const scale = (0.6 + 0.4 * landed) * (1 + 0.4 * lifted);
  const opacity = landed * (1 - lifted);
  return (
    <div
      style={{
        position: "absolute",
        left: press.x - r,
        top: press.y - r,
        width: 2 * r,
        height: 2 * r,
        borderRadius: "50%",
        background: "rgba(255,255,255,0.28)",
        boxShadow: `inset 0 0 0 ${1.5 * pxPerDp(take)}px rgba(255,255,255,0.6)`,
        transform: `scale(${scale})`,
        opacity,
      }}
    />
  );
};
