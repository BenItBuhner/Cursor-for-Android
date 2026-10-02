import type React from "react";
import { Composition, Folder } from "remotion";
import { Plate } from "./concept/Plate";
import { StyleframeCode, StyleframeLineup, StyleframeLive, StyleframeSay } from "./concept/Styleframes";
import { TypeSheet } from "./concept/TypeSheet";
import { takes } from "./takes";
import { FRAME } from "./camera";
import { Compare, COMPARE } from "./components/Compare";
import { check, DURATION, FPS, reelsOf, THEME } from "./edit";
import { Launch, type LaunchProps } from "./Launch";

check([...reelsOf("dark"), ...reelsOf("light")]);

const DEVICES = ["phone", "foldable", "tablet"] as const;

const wide: LaunchProps = { framing: "wide", music: true, theme: THEME };
const tall: LaunchProps = { framing: "tall", music: true, theme: THEME };

export const Root: React.FC = () => (
  <>
    <Composition id="Launch" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.wide} defaultProps={wide} />
    <Composition id="LaunchVertical" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.tall} defaultProps={tall} />
    <Composition id="Compare" component={Compare} durationInFrames={COMPARE.frames} fps={FPS} {...FRAME.wide} />
    <Folder name="concept">
      <Composition id="SF1-Say" component={StyleframeSay} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF2-Code" component={StyleframeCode} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF3-Live" component={StyleframeLive} durationInFrames={2} fps={FPS} {...FRAME.tall} />
      <Composition id="SF4-Lineup" component={StyleframeLineup} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF5-Type" component={TypeSheet} durationInFrames={1} fps={FPS} {...FRAME.wide} />
      {DEVICES.map((device) => (
        <Composition
          key={device}
          id={`Plate-${device}`}
          component={Plate}
          durationInFrames={1}
          fps={FPS}
          width={takes[device].width}
          height={takes[device].height}
          defaultProps={{ take: device, frame: 0, shade: false }}
        />
      ))}
    </Folder>
  </>
);
