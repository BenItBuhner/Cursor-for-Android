import type React from "react";
import { AbsoluteFill, Audio, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import type { Framing } from "./camera";
import { AndroidCard, CLOSE, OPEN } from "./components/AndroidCard";
import { EndCard } from "./components/EndCard";
import { Hero, HeroHeadlines } from "./components/Hero";
import { Lineup } from "./components/Lineup";
import { Stage } from "./components/Stage";
import { TitleCard } from "./components/TitleCard";
import { AT } from "./edit";
import type { Theme } from "./takes";

export type LaunchProps = {
  framing: Framing;
  music: boolean;
  theme: Theme;
  /** The hero's headlines alone, white on black, for measuring their ink (camera.ts INK). */
  probe?: boolean;
};

/**
 * The cut, part by part on the beat sheet in edit.ts, over the stage and under the score, the app in [theme]: the
 * title card, which the green card opens out of; the green card, which closes into the hero's phone; the hero and the
 * lineup, cut on the beat; and the end card, which the lineup dips to the stage into.
 */
export const Launch: React.FC<LaunchProps> = ({ framing, music, theme, probe = false }) => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const scene = { framing, f, width, height };
  if (probe) {
    return (
      <AbsoluteFill style={{ background: "#000", overflow: "hidden" }}>
        <HeroHeadlines framing={framing} f={f} height={height} />
      </AbsoluteFill>
    );
  }
  return (
    <AbsoluteFill style={{ overflow: "hidden" }}>
      <Stage {...scene} />
      {f < AT.android + OPEN ? <TitleCard {...scene} /> : null}
      {f >= AT.organize && f < AT.lineup ? <Hero {...scene} theme={theme} /> : null}
      {f >= AT.android && f < AT.organize + CLOSE ? <AndroidCard {...scene} theme={theme} /> : null}
      {f >= AT.lineup && f < AT.end ? <Lineup {...scene} theme={theme} /> : null}
      {f >= AT.end ? <EndCard {...scene} /> : null}
      {music ? <Audio src={staticFile("audio/score.wav")} /> : null}
    </AbsoluteFill>
  );
};
