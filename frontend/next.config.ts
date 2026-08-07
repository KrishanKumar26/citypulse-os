import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Traces the exact dependency set the server needs, so the runtime image can
  // ship without a node_modules tree. Required by the multi-stage Dockerfile,
  // which copies .next/standalone and nothing else.
  //
  // Not on Vercel, which builds its own serverless output and needs the default
  // one. Given `standalone` it compiled the whole application, generated all 18
  // pages, and then died on the very last step:
  //
  //     Running onBuildComplete from Vercel
  //     Error: ENOENT ... .next/next-server.js.nft.json
  //
  // Before that failure was visible it was worse: an earlier deployment
  // returned the landing page and 404 for every other route — a build reported
  // successful and a site that did not work.
  //
  // VERCEL is set by Vercel's build environment, so Docker and local builds are
  // untouched.
  output: process.env.VERCEL ? undefined : "standalone",

  // The build must not succeed with type errors — CI would then report green on
  // code that does not actually compile cleanly.
  //
  // There is no `eslint` key here: Next 16 removed build-time linting in favour
  // of the ESLint CLI. Linting is a separate `npm run lint` step, which CI runs
  // and blocks on.
  typescript: { ignoreBuildErrors: false },

  poweredByHeader: false,

  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // Explicitly denies capabilities this application never uses, so a
          // compromised script cannot reach for them.
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
