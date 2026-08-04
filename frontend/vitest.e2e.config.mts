import path from "node:path";
import { defineConfig } from "vitest/config";

/**
 * End-to-end suite. Separate from `vitest.config.mts` because these specs talk
 * to a real backend on :8080 and would otherwise fail any run where the stack
 * is not up. Run with `npm run test:e2e` after `docker compose up` or a local
 * `./mvnw spring-boot:run`.
 *
 * jsdom is still the environment: the API client stores the refresh token in
 * sessionStorage, so the code under test needs a DOM to behave as it does in a
 * browser.
 */
export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    include: ["e2e/**/*.e2e.ts"],
    // The backend rate-limits /api/v1/auth/** per IP, so these specs must not
    // race each other for the same window.
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 90_000,
  },
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },
});
