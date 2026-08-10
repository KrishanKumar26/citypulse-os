import type { GlyphName } from "@/components/ui/icons";

/**
 * A detection, said the way you would say it to someone who does not work here.
 *
 * The Command Center's cards are read by two people with nothing in common. One
 * is deciding whether to divert traffic in the next ten minutes; the other is a
 * citizen, a journalist or an official who wants to know whether the road is
 * bad. Until now both got the same thing — the observation, the baseline, the
 * deviation — and only the first could use it.
 *
 * So the card leads with what a reader can act on and keeps every figure, one
 * click down, in a panel that is explicitly for the other reader.
 *
 * **Nothing here is data.** Every string is derived from the metric, the
 * direction and how far from normal the reading is — all of which the detection
 * already established. This module invents no number, no threshold and no
 * measurement; it decides wording. If it were given a metric it does not know,
 * it says so plainly rather than guessing what that metric means.
 */

export interface SituationCopy {
  /** The short name of the situation: "Heavy Traffic". */
  title: string;
  icon: GlyphName;
  /** One sentence a citizen can act on. */
  headline: string;
  /** What was seen, in units a citizen recognises. */
  reading: string;
  /** What is usual here at this hour, in the same units. */
  usual: string;
  /** What the reading implies on the ground. */
  meaning: string;
  /**
   * General guidance for this kind of situation.
   *
   * Used only when no alert rule has attached a recommendation. It is generic
   * by construction — derived from the metric and direction, not from this
   * zone — and the card labels it as such, because a rule's recommendation is a
   * different kind of claim and the two must not be confused.
   */
  guidance: string;
  /** How the raw observation is labelled in the technical panel. */
  technicalLabel: string;
}

/** How far from normal, in words. Thresholds are wording, not judgement. */
function intensity(ratio: number): "slightly" | "" | "much" {
  const distance = ratio >= 1 ? ratio : 1 / ratio;
  if (distance >= 2) return "much";
  if (distance >= 1.3) return "";
  return "slightly";
}

function phrase(word: string, direction: "higher" | "lower"): string {
  return word ? `${word} ${direction}` : direction;
}

const decimal = (value: number, places = 1) =>
  value.toLocaleString(undefined, {
    minimumFractionDigits: places,
    maximumFractionDigits: places,
  });

const whole = (value: number) => Math.round(value).toLocaleString();

/**
 * The situation, described.
 *
 * `observed` and `baseline` arrive in the units the pipeline stores — an
 * occupancy ratio is 1.62, not 162 — and each entry converts its own, so the
 * card cannot show one number and the sentence another.
 */
export function describeSituation(
  metric: string,
  observed: number,
  baseline: number,
): SituationCopy {
  const ratio = baseline === 0 ? 1 : observed / baseline;
  const up = ratio >= 1;
  const word = intensity(ratio);
  const move = phrase(word, up ? "higher" : "lower");

  switch (metric) {
    case "vehicle_count":
      return {
        title: up ? "Heavy Traffic" : "Unusually Quiet",
        icon: "car",
        headline: `Traffic is ${move} than normal right now.`,
        reading: `${whole(observed)} vehicles detected`,
        usual: `Usually around ${whole(baseline)} vehicles are seen at this time.`,
        meaning: up
          ? "Roads in this area may be heavily congested."
          : "Far fewer vehicles than usual — often a closure, a diversion or a quiet spell.",
        guidance: up
          ? "Consider an alternate route."
          : "No action needed. Check for a road closure if this persists.",
        technicalLabel: "Current vehicles",
      };

    case "occupancy_ratio":
      return {
        title: up ? "Heavy Traffic" : "Light Traffic",
        icon: "car",
        headline: `Roads here are ${move === "lower" ? "emptier" : move} than normal right now.`,
        reading: `${whole(observed * 100)}% of road capacity in use`,
        usual: `Usually around ${whole(baseline * 100)}% is in use at this time.`,
        meaning: up
          ? observed > 1
            ? "There is more traffic than these roads are built to carry. Expect queues."
            : "Roads in this area may be heavily congested."
          : "Roads are clearer than they usually are at this hour.",
        guidance: up
          ? "Consider an alternate route or allow extra time."
          : "No action needed.",
        technicalLabel: "Current occupancy",
      };

    case "average_speed_kph":
      return {
        title: up ? "Free-Flowing Traffic" : "Slow Traffic",
        icon: "gauge",
        headline: `Traffic is moving ${move === "higher" ? "faster" : "slower"} than normal right now.`,
        reading: `${decimal(observed)} km/h average speed`,
        usual: `Usually around ${decimal(baseline)} km/h at this time.`,
        meaning: up
          ? "Vehicles are moving more freely than usual here."
          : "Vehicles are crawling. Journeys through this area will take longer.",
        guidance: up ? "No action needed." : "Allow extra travel time, or use another route.",
        technicalLabel: "Current average speed",
      };

    case "risk_score":
      return {
        title: up ? "Raised Overall Risk" : "Conditions Better Than Usual",
        icon: "warning",
        headline: `Overall conditions here are ${move === "higher" ? "worse" : "better"} than normal right now.`,
        reading: `${whole(observed)} out of 100 on the combined score`,
        usual: `Usually around ${whole(baseline)} out of 100 at this time.`,
        meaning: up
          ? "Traffic, air quality, weather and incidents together are worse here than they usually are."
          : "Traffic, air quality, weather and incidents together are better here than they usually are.",
        guidance: up
          ? "Check conditions before travelling through this area."
          : "No action needed.",
        technicalLabel: "Current risk score",
      };

    case "aqi":
      return {
        title: up ? "Poor Air Quality" : "Cleaner Air Than Usual",
        icon: "wind",
        headline: `Air quality here is ${move === "higher" ? "worse" : "better"} than normal right now.`,
        reading: `AQI ${whole(observed)}`,
        usual: `Usually around AQI ${whole(baseline)} at this time.`,
        meaning: up
          ? "The air is more polluted here than it usually is at this hour."
          : "The air is cleaner here than it usually is at this hour.",
        guidance: up
          ? "Those sensitive to air quality may want to limit time outdoors."
          : "No action needed.",
        technicalLabel: "Current AQI",
      };

    default:
      // A metric this module has not been taught. It states what it can — the
      // reading, the usual and the direction — and claims nothing about what
      // the measure means on the ground, because it does not know.
      return {
        title: up ? "Reading Above Normal" : "Reading Below Normal",
        icon: "activity",
        headline: `This measure is ${move} than normal right now.`,
        reading: `${decimal(observed, 2)} recorded`,
        usual: `Usually around ${decimal(baseline, 2)} at this time.`,
        meaning: "This zone is behaving unlike itself for this time of day.",
        guidance: "Open Technical Details for the underlying figures.",
        technicalLabel: "Current value",
      };
  }
}
