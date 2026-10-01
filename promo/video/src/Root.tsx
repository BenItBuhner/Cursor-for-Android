import type React from "react";
import { Composition } from "remotion";
import { FRAME } from "./camera";
import { Compare, COMPARE } from "./components/Compare";
import { check, DURATION, FPS, reelsOf, THEME } from "./edit";
import { Launch, type LaunchProps } from "./Launch";

check([...reelsOf("dark"), ...reelsOf("light")]);

const wide: LaunchProps = { framing: "wide", music: true, theme: THEME };
const tall: LaunchProps = { framing: "tall", music: true, theme: THEME };

export const Root: React.FC = () => (
  <>
    <Composition id="Launch" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.wide} defaultProps={wide} />
    <Composition id="LaunchVertical" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.tall} defaultProps={tall} />
    <Composition id="Compare" component={Compare} durationInFrames={COMPARE.frames} fps={FPS} {...FRAME.wide} />
  </>
);
