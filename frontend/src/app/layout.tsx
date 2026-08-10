import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { THEME_INIT_SCRIPT } from "@/lib/theme";

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
  // One per scheme. A single value left the phone's status bar in the dark
  // theme's near-black while the page under it was white — the only part of
  // the product the toggle could not reach, because it is painted by the OS.
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#080b14" },
    { media: "(prefers-color-scheme: light)", color: "#f6f8fc" },
  ],
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    // suppressHydrationWarning because the inline script below writes
    // data-theme onto this element before React sees it. The server cannot know
    // which theme the reader is in — that is the whole reason the script exists
    // — so the attribute legitimately differs between the server's HTML and the
    // client's first read, and without this React logs a mismatch on every load.
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <head>
        {/*
          Runs before the first paint, ahead of any stylesheet or markup.
          Applying the theme in an effect instead would run after hydration,
          which is after the browser has painted: a reader who chose light would
          see the dark theme flash past on every navigation.
        */}
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
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
