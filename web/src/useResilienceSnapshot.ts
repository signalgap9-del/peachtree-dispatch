import { useEffect, useState } from "react";

import { getResilienceSnapshot, subscribeResilienceSnapshot, type ResilienceSnapshot } from "./resilience";

export function useResilienceSnapshot(): ResilienceSnapshot {
  const [snapshot, setSnapshot] = useState(() => getResilienceSnapshot());

  useEffect(() => subscribeResilienceSnapshot(setSnapshot), []);

  return snapshot;
}
