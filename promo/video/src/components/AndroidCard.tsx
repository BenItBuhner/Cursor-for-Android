import type React from "react";
import { cameraAt, HERO_PATHS, screenOf, type Framing } from "../camera";
import { AT, HEROES } from "../edit";
import { easeIn, easeOut, progress } from "../math";
import type { Theme } from "../takes";
import { COLOR } from "../theme";
import { Headline, LEADING } from "./Headline";
import { iconCentre } from "./TitleCard";

/** How far the camera pushes in on the news, at an even pace from the open to the close. */
const PUSH = 0.09;
/** Frames the green takes to open from the icon's footprint to the whole frame. */
export const OPEN = 16;
/** Frames the green takes to close into the phone once the hero has cut in under it. */
export const CLOSE = 14;

/**
 * The news, full bleed in Android's green: it opens out of the title card's icon, a disc growing from where the icon
 * stands until it fills the frame, with the headline rising into it as it does; the headline wipes out on the beat,
 * and the green closes over the hero's first frames into a disc that shrinks away into the phone as it rises in, so
 * the green hands the frame to the phone rather than cutting.
 */
export const AndroidCard: React.FC<{ framing: Framing; theme: Theme; f: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  width,
  height,
}) => {
  const size = framing === "wide" ? 216 : 184;
  const lines = framing === "wide" ? ["Now on Android."] : ["Now on", "Android."];
  const open = easeOut(progress(f, AT.android, AT.android + OPEN));
  const close = easeIn(progress(f, AT.organize, AT.organize + CLOSE));
  let clipPath: string | undefined;
  if (open < 1) {
    const [cx, cy] = iconCentre(framing, AT.android, width);
    const reach = Math.hypot(Math.max(cx, 1 - cx) * width, Math.max(cy, 1 - cy) * height);
    clipPath = `circle(${(reach * open).toFixed(1)}px at ${(cx * 100).toFixed(2)}% ${(cy * 100).toFixed(2)}%)`;
  } else if (close > 0) {
    const screen = screenOf(framing, HEROES[theme].take, cameraAt(HERO_PATHS[theme][framing], f));
    const cx = screen.left + screen.width / 2;
    const cy = Math.min(screen.top + screen.height / 2, height * 0.96);
    const reach = Math.hypot(Math.max(cx, width - cx), Math.max(cy, height - cy));
    clipPath = `circle(${(reach * (1 - close)).toFixed(1)}px at ${cx.toFixed(1)}px ${cy.toFixed(1)}px)`;
  }
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.android, clipPath }}>
      <div style={{ position: "absolute", inset: 0, transform: `scale(${1 + PUSH * progress(f, AT.android, AT.organize + CLOSE)})` }}>
        <Headline
          lines={lines}
          f={f}
          at={AT.android + 4}
          until={AT.organize}
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
