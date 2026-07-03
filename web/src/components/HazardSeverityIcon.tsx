import { AlertTriangle, CloudRain, Droplets, Flame, ShieldCheck, Snowflake, Wind } from "lucide-react";

import type { HazardKind } from "../types";

export function HazardSeverityIcon({ hazard, severity, label }: { hazard: HazardKind; severity: string; label?: string }) {
  const Icon = hazardIcon(hazard);
  return (
    <span className={`hazard-severity-icon ${severity}`} aria-label={label ?? `${hazard} ${severity}`}>
      <Icon size={13} />
    </span>
  );
}

function hazardIcon(hazard: HazardKind) {
  if (hazard === "flood") return Droplets;
  if (hazard === "rain") return CloudRain;
  if (hazard === "wind") return Wind;
  if (hazard === "heat") return Flame;
  if (hazard === "winter") return Snowflake;
  if (hazard === "alert") return AlertTriangle;
  return ShieldCheck;
}
