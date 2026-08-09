import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "CityPulse OS — Urban Intelligence Platform",
    template: "%s · CityPulse OS",
  },
  description:
    "CityPulse OS correlates traffic, weather, air quality, events and incidents into one "
    + "intelligence layer: observe, predict, simulate and act on city conditions.",
  applicationName: "CityPulse OS",
  robots: { index: true, follow: true },

  // Without these, the link pasted into a message renders as a bare URL. The
  // card image is generated at opengraph-image.tsx rather than committed as a
  // binary, so it cannot drift from the wording above it.
  openGraph: {
    type: "website",
    siteName: "CityPulse OS",
    locale: "en_GB",
    title: "CityPulse OS — Urban Intelligence Platform",
    description:
      "Traffic, weather, air quality, events and incidents correlated into one "
      + "intelligence layer: observe, predict, simulate and act.",
  },
  twitter: {
    card: "summary_large_image",
    title: "CityPulse OS — Urban Intelligence Platform",
    description:
      "Traffic, weather, air quality, events and incidents correlated into one "
      + "intelligence layer: observe, predict, simulate and act.",
  },
};

export const viewport: Viewport = {
  // Must equal --color-surface-base. It is the one colour that cannot be a
  // token: the browser paints its chrome with it before any stylesheet loads.
  // It was left at the old near-black through the palette change, so the phone
  // status bar sat a shade off the page it framed — pinned by a test now.
  themeColor: "#080b14",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="min-h-screen antialiased">
        {/* Lets keyboard users bypass the sidebar and topbar (PRD §32). */}
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-accent focus:px-4 focus:py-2 focus:text-sm focus:text-surface-base"
        >
          Skip to main content
        </a>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
