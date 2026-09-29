import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { clamp01, easeOut, progress, snap } from "../math";
import { COLOR, DISPLAY, SANS } from "../theme";
import { AppIcon } from "./AppIcon";

export const REPO = "github.com/BenItBuhner/cursor-for-android";
export const DISCLAIMER = "Unofficial client for Cursor Cloud Agents. Not affiliated with Anysphere, Inc.";

/** The close: the icon lands on the last hit, the name and where to get it rise under it, and the small print. */
export const EndCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const wide = framing === "wide";
  const icon = wide ? 168 : 196;
  const title = wide ? 112 : 96;
  const url = wide ? 36 : 34;
  const rise = (at: number, by: number) => {
    const p = easeOut(progress(f, at, at + 20));
    return { opacity: clamp01((f - at + 1) / 8), transform: `translateY(${(1 - p) * by}px)` };
  };
  const landed = snap(progress(f, AT.end, AT.end + 16));
  const push = 1 + 0.025 * progress(f, AT.end, AT.end + 240);
  return (
    <div style={{ position: "absolute", inset: 0, transform: `scale(${push})` }}>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: height * (wide ? 0.25 : 0.3),
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
        }}
      >
        <div style={{ transform: `scale(${0.6 + 0.4 * landed})`, opacity: clamp01((f - AT.end + 1) / 3) }}>
          <AppIcon size={icon} glow={0.3} />
        </div>
        <div
          style={{
            marginTop: icon * 0.26,
            fontFamily: DISPLAY,
            fontWeight: 700,
            fontSize: title,
            lineHeight: 1.05,
            letterSpacing: "-0.042em",
            color: COLOR.ink,
            whiteSpace: "nowrap",
            ...rise(AT.end + 5, title * 0.35),
          }}
        >
          Cursor for Android
        </div>
        <div
          style={{
            marginTop: title * 0.34,
            fontFamily: SANS,
            fontWeight: 500,
            fontSize: url,
            letterSpacing: "-0.01em",
            color: COLOR.inkSoft,
            whiteSpace: "nowrap",
            ...rise(AT.end + 12, url * 0.6),
          }}
        >
          {REPO}
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: height * (wide ? 0.07 : 0.06),
          textAlign: "center",
          fontFamily: SANS,
          fontWeight: 400,
          fontSize: wide ? 22 : 24,
          lineHeight: 1.4,
          color: COLOR.inkSoft,
          padding: `0 ${width * 0.08}px`,
          ...rise(AT.end + 20, 10),
        }}
      >
        {DISCLAIMER}
      </div>
    </div>
  );
};
