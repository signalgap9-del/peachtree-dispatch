const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID ?? "";
const domain = import.meta.env.VITE_COGNITO_DOMAIN ?? "";
const tokenKey = "atmospath:id-token";
const accessTokenKey = "atmospath:access-token";
const verifierKey = "atmospath:pkce-verifier";
const stateKey = "atmospath:oauth-state";

export type AuthUser = {
  subject: string;
  email?: string;
  username?: string;
};

function encode(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function randomValue() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return encode(bytes);
}

async function challenge(verifier: string) {
  return encode(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
}

export function authConfigured() {
  return Boolean(clientId && domain);
}

export function idToken() {
  return sessionStorage.getItem(tokenKey);
}

export function accessToken() {
  return sessionStorage.getItem(accessTokenKey);
}

export function currentUser(): AuthUser | null {
  const token = idToken();
  if (!token) return null;
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replaceAll("-", "+").replaceAll("_", "/"))) as Record<string, string | number>;
    if (typeof payload.exp === "number" && payload.exp * 1000 < Date.now()) {
      sessionStorage.removeItem(tokenKey);
      return null;
    }
    return {
      subject: String(payload.sub),
      email: payload.email ? String(payload.email) : undefined,
      username: payload["cognito:username"] ? String(payload["cognito:username"]) : undefined,
    };
  } catch {
    sessionStorage.removeItem(tokenKey);
    return null;
  }
}

export async function login() {
  if (!authConfigured()) return;
  const verifier = randomValue();
  const state = randomValue();
  sessionStorage.setItem(verifierKey, verifier);
  sessionStorage.setItem(stateKey, state);
  const redirectUri = `${window.location.origin}/`;
  const params = new URLSearchParams({
    client_id: clientId,
    response_type: "code",
    scope: "openid email profile",
    redirect_uri: redirectUri,
    state,
    code_challenge_method: "S256",
    code_challenge: await challenge(verifier),
  });
  window.location.assign(`https://${domain}/oauth2/authorize?${params}`);
}

export async function completeLogin() {
  const params = new URLSearchParams(window.location.search);
  const code = params.get("code");
  if (!code) return false;
  const expectedState = sessionStorage.getItem(stateKey);
  const verifier = sessionStorage.getItem(verifierKey);
  if (!expectedState || params.get("state") !== expectedState || !verifier) throw new Error("Invalid login state");
  const body = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    redirect_uri: `${window.location.origin}/`,
    code_verifier: verifier,
  });
  const response = await fetch(`https://${domain}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!response.ok) throw new Error("Sign-in could not be completed");
  const tokens = await response.json() as { id_token: string; access_token: string };
  sessionStorage.setItem(tokenKey, tokens.id_token);
  sessionStorage.setItem(accessTokenKey, tokens.access_token);
  sessionStorage.removeItem(stateKey);
  sessionStorage.removeItem(verifierKey);
  window.history.replaceState({}, "", "/");
  return true;
}

export function logout() {
  sessionStorage.removeItem(tokenKey);
  sessionStorage.removeItem(accessTokenKey);
  if (!authConfigured()) return;
  const params = new URLSearchParams({ client_id: clientId, logout_uri: `${window.location.origin}/` });
  window.location.assign(`https://${domain}/logout?${params}`);
}
