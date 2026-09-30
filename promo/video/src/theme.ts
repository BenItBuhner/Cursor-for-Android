import { loadFont as loadGoogleSans } from "@remotion/google-fonts/GoogleSans";
import { loadFont as loadInstrumentSans } from "@remotion/google-fonts/InstrumentSans";

/**
 * Everything the video says, set as cursor.com sets its own type: an open grotesque at a regular weight, tracked in a
 * little tighter the larger it runs ([TRACK]).
 */
export const SANS = loadInstrumentSans("normal", { weights: ["400", "500"], subsets: ["latin"] }).fontFamily;

/** A device's system UI (the status bar, the notification shade), in Android's own face. */
export const SYSTEM = loadGoogleSans("normal", { weights: ["400", "500"], subsets: ["latin"] }).fontFamily;

export const TRACK = {
  /** Headlines, the wordmark, the end card's name. */
  display: "-0.03em",
  /** A line of small print or a URL. */
  text: "-0.005em",
};

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
  /** The status bar's clock and icons over a dark screen, and over a light one. */
  statusText: "#F2F2F2",
  statusTextOnLight: "#1B1B1F",
};
