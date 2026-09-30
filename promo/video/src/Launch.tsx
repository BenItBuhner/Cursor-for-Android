import type React from "react";
import { AbsoluteFill, Audio, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import type { Framing } from "./camera";
import { AndroidCard } from "./components/AndroidCard";
import { EndCard } from "./components/EndCard";
import { Hero } from "./components/Hero";
import { Lineup } from "./components/Lineup";
import { TitleCard } from "./components/TitleCard";
import { AT } from "./edit";
import type { Theme } from "./takes";
import { COLOR } from "./theme";

export type LaunchProps = { framing: Framing; music: boolean; theme: Theme };

/** The cut, part by part on the beat sheet in edit.ts, with the score under it, the app in [theme]. */
export const Launch: React.FC<LaunchProps> = ({ framing, music, theme }) => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const scene = { framing, f, width, height };
  return (
    <AbsoluteFill style={{ background: COLOR.stage, overflow: "hidden" }}>
      {f < AT.android ? <TitleCard {...scene} /> : null}
      {f >= AT.android && f < AT.organize ? <AndroidCard {...scene} /> : null}
      {f >= AT.organize && f < AT.lineup ? <Hero {...scene} theme={theme} /> : null}
      {f >= AT.lineup && f < AT.end ? <Lineup {...scene} theme={theme} /> : null}
      {f >= AT.end ? <EndCard {...scene} /> : null}
      {music ? <Audio src={staticFile("audio/score.wav")} /> : null}
    </AbsoluteFill>
  );
};
