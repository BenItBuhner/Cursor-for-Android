import { Easing } from "remotion";

export const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
export const lerp = (a: number, b: number, p: number) => a + (b - a) * p;

/** How far frame [t] is through [from, to), 0 before and 1 after. */
export const progress = (t: number, from: number, to: number) => (to <= from ? (t >= from ? 1 : 0) : clamp01((t - from) / (to - from)));

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
/** A quick move that lands with a hair of overshoot. */
export const snap = Easing.bezier(0.2, 1.3, 0.35, 1);
