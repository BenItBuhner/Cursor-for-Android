import type React from "react";
import { easeOut, progress } from "../math";
import { COLOR, DISPLAY } from "../theme";

/** Frames a word takes to rise into place, and the frames between one word's start and the next's. */
const RISE = 16;
const STAGGER = 3;
/** Frames the headline takes to clear before the next moment's. */
const CLEAR = 7;

/**
 * [lines] set at [size] pixels, its top left at [x, y] (or centred on [x] with [align] "center"): each word rises into
 * place from under its line at frame [at], one after another, and the lot clears upward just before [until].
 */
export const Headline: React.FC<{
  lines: readonly string[];
  f: number;
  at: number;
  until?: number;
  size: number;
  x: number;
  y: number;
  align?: "left" | "center";
  color?: string;
  weight?: 700 | 800;
  /** Frame each word starts rising at, when not one after another from [at]. */
  wordAt?: number[];
}> = ({ lines, f, at, until, size, x, y, align = "left", color = COLOR.ink, weight = 700, wordAt }) => {
  let n = 0;
  const leaving = until === undefined ? 0 : progress(f, until - CLEAR, until) ** 2;
  return (
    <div
      style={{
        position: "absolute",
        left: align === "center" ? 0 : x,
        right: align === "center" ? 0 : undefined,
        top: y,
        fontFamily: DISPLAY,
        fontWeight: weight,
        fontSize: size,
        lineHeight: 1,
        letterSpacing: "-0.042em",
        color,
        textAlign: align,
        whiteSpace: "nowrap",
      }}
    >
      {lines.map((line) => (
        <div key={line} style={{ display: "block" }}>
          {line.split(" ").map((word, j, words) => {
            const start = wordAt?.[n] ?? at + STAGGER * n;
            n++;
            const rise = easeOut(progress(f, start, start + RISE));
            const shown = rise * (1 - leaving);
            const offset = (1 - rise) * 1.08 + leaving * -1.08;
            return (
              <span
                key={`${word}-${j}`}
                style={{
                  display: "inline-block",
                  overflow: "hidden",
                  verticalAlign: "top",
                  padding: "0.04em 0.02em 0.14em",
                  marginTop: "-0.04em",
                  marginBottom: "-0.14em",
                  marginLeft: "-0.02em",
                  marginRight: j < words.length - 1 ? "0.2em" : "-0.02em",
                }}
              >
                <span style={{ display: "inline-block", transform: `translateY(${offset * 100}%)`, opacity: shown > 0 ? 1 : 0 }}>{word}</span>
              </span>
            );
          })}
        </div>
      ))}
    </div>
  );
};
