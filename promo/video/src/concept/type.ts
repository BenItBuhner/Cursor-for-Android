import type React from "react";
import { continueRender, delayRender, staticFile } from "remotion";

/**
 * The concept's one family: Google Sans Flex, self-hosted so every axis is there to set. Weight, width and roundness are
 * fixed per role; optical size always follows the size the type is drawn at; grade is the only axis that moves.
 */
export const FLEX = "Google Sans Flex";

if (typeof document !== "undefined" && typeof FontFace !== "undefined") {
  const handle = delayRender("Loading Google Sans Flex");
  const face = new FontFace(FLEX, `url(${staticFile("fonts/GoogleSansFlex.woff2")}) format("woff2")`, { weight: "1 1000", stretch: "25% 151%" });
  face
    .load()
    .then((loaded) => {
      (document.fonts as unknown as { add(face: FontFace): void }).add(loaded);
      continueRender(handle);
    })
    .catch((error: unknown) => {
      throw error;
    });
}

/** A role in the type system: its weight and width, and the tracking it is set at, in ems, at display sizes. */
export type Role = { wght: number; wdth: number; tracking: number; leading: number };

export const ROLE = {
  /** Headlines: one weight, never animated through a middle one. */
  display: { wght: 620, wdth: 100, tracking: -0.035, leading: 0.94 },
  /** The few words that sit small beside the product: a caption's weight and open tracking. */
  caption: { wght: 450, wdth: 100, tracking: 0.0, leading: 1.25 },
} satisfies Record<string, Role>;

/**
 * The CSS for [role] at [size] pixels on screen, at [grade] (0 to 100: weight without width, so a line never reflows).
 * Optical size is the on-screen size, clamped to the font's range, so a line pushed in on by the camera redraws for
 * the size it reaches, as the type does on the device.
 */
export function typeStyle(role: Role, size: number, grade = 0): React.CSSProperties {
  const opsz = Math.max(6, Math.min(144, size));
  return {
    fontFamily: FLEX,
    fontSize: size,
    lineHeight: role.leading,
    letterSpacing: `${role.tracking}em`,
    fontVariationSettings: `"wght" ${role.wght}, "wdth" ${role.wdth}, "opsz" ${opsz.toFixed(1)}, "GRAD" ${grade.toFixed(1)}, "ROND" 0`,
    fontOpticalSizing: "none",
    fontKerning: "normal",
  };
}
