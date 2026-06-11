export function notify(message: string) {
  window.dispatchEvent(new CustomEvent("atmospath:toast", { detail: message }));
}

export function saveRoute(route: unknown) {
  const existing = JSON.parse(localStorage.getItem("atmospath:saved-routes") ?? "[]") as unknown[];
  localStorage.setItem("atmospath:saved-routes", JSON.stringify([route, ...existing].slice(0, 20)));
  notify("Route saved to this browser.");
}
