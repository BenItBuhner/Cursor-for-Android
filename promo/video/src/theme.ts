import { loadFont as loadInter } from "@remotion/google-fonts/Inter";
import { loadFont as loadInterTight } from "@remotion/google-fonts/InterTight";

export const DISPLAY = loadInterTight("normal", { weights: ["600", "700", "800"], subsets: ["latin"] }).fontFamily;
export const SANS = loadInter("normal", { weights: ["400", "500", "600"], subsets: ["latin"] }).fontFamily;

export const COLOR = {
  /** The stage: Cursor's warm off-white. */
  stage: "#F7F7F4",
  ink: "#14120B",
  inkSoft: "#6B6963",
  android: "#3DDC84",
  /** The launcher icon's tile, behind the cube. */
  launcher: "#14120B",
  /** A device's glass and frame, graphite. */
  body: "#141416",
  rim: "#3C3C42",
  statusText: "#F2F2F2",
};
