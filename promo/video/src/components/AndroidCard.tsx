import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { progress } from "../math";
import { COLOR } from "../theme";
import { Headline } from "./Headline";

/** The news, full bleed in Android's green, cut in and out on the beat. */
export const AndroidCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, height }) => {
  const size = framing === "wide" ? 216 : 184;
  const lines = framing === "wide" ? ["Now on Android."] : ["Now on", "Android."];
  const push = 1 + 0.035 * progress(f, AT.android, AT.start);
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.android }}>
      <div style={{ position: "absolute", inset: 0, transform: `scale(${push})` }}>
        <Headline
          lines={lines}
          f={f}
          at={AT.android}
          size={size}
          x={0}
          y={(height - lines.length * size) / 2 - size * 0.04}
          align="center"
          weight={800}
          wordAt={[AT.android, AT.android + 4, AT.android + 8]}
        />
      </div>
    </div>
  );
};
