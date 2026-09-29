import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { clamp01, easeOut, lerp, progress, snap } from "../math";
import { COLOR, DISPLAY } from "../theme";
import { AppIcon } from "./AppIcon";

/** "Cursor" set in the display face at 1px, tracked as the headlines are. */
const WORDMARK_EM = 2.86;

/**
 * The open: the app's icon lands in the middle of the frame, then slides aside for the wordmark, which comes out from
 * behind it on the next beat.
 */
export const TitleCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const icon = framing === "wide" ? 232 : 184;
  const size = framing === "wide" ? 210 : 164;
  const gap = icon * 0.24;
  const word = size * WORDMARK_EM;
  const lockup = icon + gap + word;
  const landed = snap(progress(f, AT.title, AT.title + 16));
  const slide = easeOut(progress(f, AT.wordmark, AT.wordmark + 22));
  const iconLeft = lerp(width / 2 - icon / 2, width / 2 - lockup / 2, slide);
  const wordLeft = width / 2 - lockup / 2 + icon + gap / 2;
  const push = 1 + 0.03 * progress(f, AT.title, AT.android);
  return (
    <div style={{ position: "absolute", inset: 0, transform: `scale(${push})` }}>
      <div
        style={{
          position: "absolute",
          left: wordLeft,
          top: height / 2 - size * 0.6,
          width: word + gap,
          height: size * 1.2,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            paddingLeft: gap / 2,
            transform: `translateX(${(1 - slide) * -100}%)`,
            fontFamily: DISPLAY,
            fontWeight: 700,
            fontSize: size,
            lineHeight: 1.2,
            letterSpacing: "-0.042em",
            color: COLOR.ink,
            whiteSpace: "nowrap",
          }}
        >
          Cursor
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: iconLeft,
          top: height / 2 - icon / 2,
          transform: `scale(${0.55 + 0.45 * landed})`,
          opacity: clamp01((f - AT.title + 1) / 3),
        }}
      >
        <AppIcon size={icon} glow={0.35} />
      </div>
    </div>
  );
};
