import { describe, expect, it } from "vitest";
import { formatArea, formatInstant, formatNumber, formatRelative } from "./format";

describe("formatNumber", () => {
  it("groups thousands", () => {
    expect(formatNumber(17800)).toBe("17,800");
  });

  it("renders an em dash for absent values rather than 'null' or 'NaN'", () => {
    expect(formatNumber(null)).toBe("—");
    expect(formatNumber(undefined)).toBe("—");
    expect(formatNumber(Number.NaN)).toBe("—");
  });

  it("handles zero as a real value, not as absent", () => {
    expect(formatNumber(0)).toBe("0");
  });
});

describe("formatArea", () => {
  it("accepts the decimal strings the API returns", () => {
    // Areas arrive as strings so backend precision is not lost to float.
    expect(formatArea("24.50")).toBe("24.5 km²");
  });

  it("accepts numbers too", () => {
    expect(formatArea(4.2)).toBe("4.2 km²");
  });

  it("returns an em dash for absent or unparseable values", () => {
    expect(formatArea(null)).toBe("—");
    expect(formatArea("not-a-number")).toBe("—");
  });
});

describe("formatInstant", () => {
  it("renders a UTC instant in the requested city timezone", () => {
    // 12:00 UTC is 17:30 in Asia/Kolkata (UTC+5:30).
    const formatted = formatInstant("2026-08-03T12:00:00Z", "Asia/Kolkata");
    expect(formatted).toContain("17:30");
  });

  it("returns an em dash for absent or invalid input", () => {
    expect(formatInstant(null)).toBe("—");
    expect(formatInstant("not-a-date")).toBe("—");
  });
});

describe("formatRelative", () => {
  it("describes recent instants coarsely", () => {
    const twoMinutesAgo = new Date(Date.now() - 2 * 60 * 1000).toISOString();
    expect(formatRelative(twoMinutesAgo)).toBe("2 min ago");
  });

  it("collapses the last minute to 'just now'", () => {
    expect(formatRelative(new Date().toISOString())).toBe("just now");
  });

  it("returns an em dash for absent input", () => {
    expect(formatRelative(null)).toBe("—");
  });
});
