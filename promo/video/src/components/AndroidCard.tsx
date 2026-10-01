import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { progress } from "../math";
import { COLOR } from "../theme";
import { Headline, LEADING } from "./Headline";

/** How far the camera pushes in on the news, at an even pace from cut to cut. */
const PUSH = 0.09;

/** The news, full bleed in Android's green, cut in and out on the beat. */
export const AndroidCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, height }) => {
  const size = framing === "wide" ? 216 : 184;
  const lines = framing === "wide" ? ["Now on Android."] : ["Now on", "Android."];
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.android }}>
      <div style={{ position: "absolute", inset: 0, transform: `scale(${1 + PUSH * progress(f, AT.android, AT.organize)})` }}>
        <Headline
          lines={lines}
          f={f}
          at={AT.android}
          size={size}
          x={0}
          y={(height - lines.length * size * LEADING) / 2}
          align="center"
          wordAt={[AT.android, AT.android + 4, AT.android + 8]}
        />
      </div>
    </div>
  );
};
