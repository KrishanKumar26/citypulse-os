import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Traces the exact dependency set the server needs, so the runtime image can
  // ship without a node_modules tree. Required by the multi-stage Dockerfile.
  output: "standalone",

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
