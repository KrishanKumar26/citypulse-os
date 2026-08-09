import { ImageResponse } from "next/og";

/**
 * The card a pasted link unfurls into.
 *
 * Generated rather than committed as a PNG, for the same reason the rest of
 * this page stopped hardcoding its figures: an image checked into the tree
 * drifts from the copy beside it and nothing fails when it does. This is built
 * from the same words, at build time, using next/og — already part of Next, so
 * no dependency is added for it.
 *
 * Deliberately plain. A card is read at thumbnail size in a chat list, where a
 * diagram becomes noise and only the name and one line survive.
 */
export const alt = "CityPulse OS — an intelligence layer for understanding the city";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

// The tokens from globals.css. Repeated as literals because this renders in a
// separate image pipeline that never loads the stylesheet.
const SURFACE = "#080b14";
const ACCENT = "#22d3ee";
const PRIMARY = "#f1f5f9";
const SECONDARY = "#a3b0c4";
const TERTIARY = "#6b7a94";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          background: SURFACE,
          padding: "72px",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "16px" }}>
          <div
            style={{
              width: 40,
              height: 40,
              borderRadius: 10,
              border: `2px solid ${ACCENT}`,
              display: "flex",
            }}
          />
          <div style={{ color: PRIMARY, fontSize: 30, fontWeight: 600 }}>CityPulse OS</div>
        </div>

        <div style={{ display: "flex", flexDirection: "column" }}>
          <div
            style={{
              color: ACCENT,
              fontSize: 22,
              letterSpacing: 4,
              textTransform: "uppercase",
            }}
          >
            Observe · Predict · Simulate · Act
          </div>
          <div
            style={{
              color: PRIMARY,
              fontSize: 68,
              fontWeight: 600,
              lineHeight: 1.1,
              marginTop: 24,
              maxWidth: 900,
            }}
          >
            An intelligence layer for understanding the city.
          </div>
          <div style={{ color: SECONDARY, fontSize: 26, marginTop: 24, maxWidth: 860 }}>
            Traffic, weather, air quality, events and incidents, correlated per zone
            and per window.
          </div>
        </div>

        <div style={{ color: TERTIARY, fontSize: 22, display: "flex" }}>
          Every figure carries where it came from — measured, modelled or synthetic.
        </div>
      </div>
    ),
    size,
  );
}
