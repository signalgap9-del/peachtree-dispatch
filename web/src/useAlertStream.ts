import { useEffect, useState } from "react";

import {
  connectAlertStream,
  disconnectAlertStream,
  getAlertStreamStatus,
  subscribeAlertStreamSnapshot,
  subscribeAlertUpdates,
  type AlertStreamSnapshot,
  type AlertStreamStatus,
} from "./alertStream";
import type { NationalRiskOverview } from "./types";

export type AlertStreamState = {
  status: AlertStreamStatus;
  lastAlert: NationalRiskOverview | null;
  lastEventAt?: string;
  reconnectAttempts: number;
  isConnected: boolean;
};

export function useAlertStream(): AlertStreamState {
  const [snapshot, setSnapshot] = useState<AlertStreamSnapshot>(() => getAlertStreamStatus());
  const [lastAlert, setLastAlert] = useState<NationalRiskOverview | null>(null);

  useEffect(() => {
    connectAlertStream();
    const unsubscribeSnapshot = subscribeAlertStreamSnapshot(setSnapshot);
    const unsubscribeAlerts = subscribeAlertUpdates(setLastAlert);
    return () => {
      unsubscribeSnapshot();
      unsubscribeAlerts();
      disconnectAlertStream();
    };
  }, []);

  return {
    status: snapshot.status,
    lastAlert,
    lastEventAt: snapshot.lastEventAt,
    reconnectAttempts: snapshot.reconnectAttempts,
    isConnected: snapshot.status === "connected",
  };
}
