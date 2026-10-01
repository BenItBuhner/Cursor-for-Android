import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { easeOut, progress } from "../math";
import { COLOR } from "../theme";
import { Headline, LEADING } from "./Headline";
import { iconCentre } from "./TitleCard";

/** How far the camera pushes in on the news, at an even pace from cut to cut. */
const PUSH = 0.09;
/** Frames the green takes to open from the icon's footprint to the whole frame. */
export const OPEN = 16;

/**
 * The news, full bleed in Android's green: it opens out of the title card's icon, a disc growing from where the icon
 * stands until it fills the frame, with the headline rising into it as it does, and cuts out on the beat.
 */
export const AndroidCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const size = framing === "wide" ? 216 : 184;
  const lines = framing === "wide" ? ["Now on Android."] : ["Now on", "Android."];
  const [cx, cy] = iconCentre(framing, AT.android, width);
  const reach = Math.hypot(Math.max(cx, 1 - cx) * width, Math.max(cy, 1 - cy) * height);
  const open = easeOut(progress(f, AT.android, AT.android + OPEN));
  const radius = open < 1 ? `${(reach * open).toFixed(1)}px` : null;
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        background: COLOR.android,
        clipPath: radius ? `circle(${radius} at ${(cx * 100).toFixed(2)}% ${(cy * 100).toFixed(2)}%)` : undefined,
      }}
    >
      <div style={{ position: "absolute", inset: 0, transform: `scale(${1 + PUSH * progress(f, AT.android, AT.organize)})` }}>
        <Headline
          lines={lines}
          f={f}
          at={AT.android + 4}
          size={size}
          x={0}
          y={(height - lines.length * size * LEADING) / 2}
          align="center"
          color={COLOR.onAndroid}
        />
      </div>
    </div>
  );
};
