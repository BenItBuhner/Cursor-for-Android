import type React from "react";
import { cameraAt, HERO_SHOTS, TALL_TOP, type Framing } from "../camera";
import { AT, HEROES, MOMENTS, shadeAt, takeFrame } from "../edit";
import { easeOut, progress } from "../math";
import type { Theme } from "../takes";
import { Device, screenHeight } from "./Device";
import { Headline, LEADING } from "./Headline";
import { Screen } from "./Screen";

/**
 * Where the hero goes in each framing: the phone's screen width at the camera's resting zoom, and the headline's size.
 * A wide frame sets the headline at [left], centred down the frame beside the phone; a tall one centres it across the
 * frame, over the phone, in the room over the screen's top ([TALL_TOP]) less [clear].
 */
const LAYOUT = {
  wide: { rest: 404, size: 150, left: 150 },
  tall: { rest: 614, size: 124, clear: 24 },
} as const;

/** Frames the phone takes to rise into the frame as the first moment starts. */
const ENTER = 28;

export const Hero: React.FC<{ framing: Framing; theme: Theme; f: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  width,
  height,
}) => {
  const reel = HEROES[theme];
  const cam = cameraAt(HERO_SHOTS[theme][framing], f);
  const w = LAYOUT[framing].rest * cam.zoom;
  const h = screenHeight(reel.take, w);
  const entered = easeOut(progress(f, AT.organize, AT.organize + ENTER));
  const x = cam.x * width - cam.fx * w;
  const y = cam.y * height - cam.fy * h + (1 - entered) * height * 0.75;
  return (
    <>
      <Device take={reel.take} x={x} y={y} width={w}>
        <Screen take={reel.take} frame={takeFrame(reel, f)} width={w} shade={shadeAt(f)} />
      </Device>
      {MOMENTS.map((moment) => {
        if (f < moment.at || f >= moment.until) return null;
        if (framing === "wide") {
          const { size, left } = LAYOUT.wide;
          const block = moment.wide.length * size * LEADING;
          return <Headline key={moment.at} lines={moment.wide} f={f} at={moment.at} until={moment.until} size={size} x={left} y={(height - block) / 2} />;
        }
        const { size, clear } = LAYOUT.tall;
        const block = moment.tall.length * size * LEADING;
        const room = TALL_TOP * height - clear;
        return (
          <Headline
            key={moment.at}
            lines={moment.tall}
            f={f}
            at={moment.at}
            until={moment.until}
            size={size}
            x={0}
            y={(room - block) / 2}
            align="center"
          />
        );
      })}
    </>
  );
};
