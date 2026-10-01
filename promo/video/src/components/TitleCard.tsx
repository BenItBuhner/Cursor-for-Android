import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { clamp01, easeOut, progress } from "../math";
import { COLOR, SANS, TRACK } from "../theme";
import { AppIcon } from "./AppIcon";

/** How far the camera pushes in on the open, at an even pace from its first frame to the cut. */
const PUSH = 0.07;

/**
 * The open: the app's icon lands in the middle of the frame, then slides aside for the wordmark, which comes out from
 * behind it on the next beat. Icon and wordmark stand in one row, laid out by the face's own widths and centred on the
 * frame, and the row is held over to the right until the slide by as much as puts the icon alone in the middle. The
 * wordmark's box starts under the icon's middle, so the word slides out from behind it.
 */
export const TitleCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f }) => {
  const icon = framing === "wide" ? 232 : 184;
  const size = framing === "wide" ? 210 : 164;
  const gap = icon * 0.24;
  const landed = easeOut(progress(f, AT.title, AT.title + 18));
  const slide = easeOut(progress(f, AT.wordmark, AT.wordmark + 26));
  const push = 1 + PUSH * progress(f, AT.title, AT.android);
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        transform: `scale(${push})`,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", transform: `translateX(calc((50% - ${icon / 2}px) * ${1 - slide}))` }}>
        <div style={{ position: "relative", zIndex: 1, transform: `scale(${0.82 + 0.18 * landed})`, opacity: clamp01((f - AT.title + 1) / 5) }}>
          <AppIcon size={icon} glow={0.35} />
        </div>
        <div style={{ marginLeft: -icon / 2, overflow: "hidden" }}>
          <div
            style={{
              paddingLeft: icon / 2 + gap,
              transform: `translateX(${(slide - 1) * 100}%)`,
              fontFamily: SANS,
              fontWeight: 500,
              fontSize: size,
              lineHeight: 1.2,
              letterSpacing: TRACK.display,
              color: COLOR.ink,
              whiteSpace: "nowrap",
            }}
          >
            Cursor
          </div>
        </div>
      </div>
    </div>
  );
};
