import type React from "react";
import { cameraAt, HERO_SHOTS, type Framing } from "../camera";
import { AT, HERO, MOMENTS, takeFrame } from "../edit";
import { progress, snap } from "../math";
import { COLOR } from "../theme";
import { Device, screenHeight } from "./Device";
import { Headline } from "./Headline";
import { Screen } from "./Screen";

/**
 * Where the hero goes in each framing: the phone's screen width at the camera's resting zoom, and the headline's size
 * and place. Tall frames keep the headline in a band at the top that the phone passes under.
 */
const LAYOUT = {
  wide: { rest: 404, size: 150, left: 150, band: 0 },
  tall: { rest: 640, size: 124, left: 0, band: 500 },
} as const;

/** Frames the phone takes to rise into the frame as the first moment starts. */
const ENTER = 24;

export const Hero: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const layout = LAYOUT[framing];
  const cam = cameraAt(HERO_SHOTS[framing], f);
  const w = layout.rest * cam.zoom;
  const h = screenHeight("phone", w);
  const entered = snap(progress(f, AT.start, AT.start + ENTER));
  const x = cam.x * width - cam.fx * w;
  const y = cam.y * height - cam.fy * h + (1 - entered) * height * 0.7;
  return (
    <>
      <Device take="phone" x={x} y={y} width={w}>
        <Screen take="phone" frame={takeFrame(HERO, f)} width={w} />
      </Device>
      {layout.band > 0 ? (
        <div
          style={{
            position: "absolute",
            left: 0,
            top: 0,
            width,
            height: layout.band,
            background: `linear-gradient(180deg, ${COLOR.stage} 0%, ${COLOR.stage} 82%, rgba(247,247,244,0) 100%)`,
          }}
        />
      ) : null}
      {MOMENTS.map((moment) => {
        if (f < moment.at || f >= moment.until) return null;
        const block = moment.lines.length * layout.size;
        return framing === "wide" ? (
          <Headline
            key={moment.at}
            lines={moment.lines}
            f={f}
            at={moment.at}
            until={moment.until}
            size={layout.size}
            x={layout.left}
            y={(height - block) / 2}
          />
        ) : (
          <Headline
            key={moment.at}
            lines={moment.lines}
            f={f}
            at={moment.at}
            until={moment.until}
            size={layout.size}
            x={0}
            y={(layout.band * 0.86 - block) / 2 + 20}
            align="center"
          />
        );
      })}
    </>
  );
};
