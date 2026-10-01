import type React from "react";
import { cameraAt, HERO_PATHS, LENS, screenOf, TALL_TOP, type Framing } from "../camera";
import { HEROES, MOMENTS, shadeAt, takeFrame } from "../edit";
import type { Theme } from "../takes";
import { Device } from "./Device";
import { Headline, LEADING } from "./Headline";
import { Screen } from "./Screen";
import { sweepAt } from "./Stage";

/**
 * Where the hero's headline goes in each framing, and its size. A wide frame sets it at [left], centred down the frame
 * beside the phone; a tall one centres it across the frame, over the phone, in the room over the screen's resting top
 * ([TALL_TOP]) less [clear].
 */
const LAYOUT = {
  wide: { size: 150, left: 150 },
  tall: { size: 124, clear: 44 },
} as const;

/** The hero's headlines alone, each standing over its moment. */
export const HeroHeadlines: React.FC<{ framing: Framing; f: number; height: number }> = ({ framing, f, height }) => (
  <>
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
        <Headline key={moment.at} lines={moment.tall} f={f} at={moment.at} until={moment.until} size={size} x={0} y={(room - block) / 2} align="center" />
      );
    })}
  </>
);

/**
 * The hero's phone at frame [f], the camera where it stands at frame [cam]: a shot held a few frames past its cut, or
 * the next held at its first, while a light carries the cut across the frame. Its headlines are [HeroHeadlines].
 */
export const Hero: React.FC<{ framing: Framing; theme: Theme; f: number; cam?: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  cam: camFrame = f,
  width,
  height,
}) => {
  const reel = HEROES[theme];
  const cam = cameraAt(HERO_PATHS[theme][framing], camFrame);
  const screen = screenOf(framing, reel.take, cam);
  const origin = `${cam.x * width}px ${cam.y * height}px`;
  return (
    <>
      <div style={{ position: "absolute", inset: 0, perspective: LENS[framing].perspective, perspectiveOrigin: origin }}>
        <div
          style={{
            position: "absolute",
            inset: 0,
            transformOrigin: origin,
            transform: `rotateX(${cam.tilt}deg) rotateY(${cam.turn}deg) rotateZ(${cam.roll}deg)`,
          }}
        >
          <Device take={reel.take} x={screen.left} y={screen.top} width={screen.width} sheen={sweepAt(f)}>
            <Screen take={reel.take} frame={takeFrame(reel, f)} width={screen.width} shade={shadeAt(f)} />
          </Device>
        </div>
      </div>
    </>
  );
};
