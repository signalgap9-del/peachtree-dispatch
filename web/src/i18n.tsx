import { createContext, useContext } from "react";

export type Language = "en" | "ko";

export const languageKey = "atmospath:language";

export const copy = {
  en: {
    "nav.home": "Home",
    "nav.map": "Map",
    "nav.dashboard": "Dashboard",
    "nav.saved": "Saved",
    "nav.alerts": "Alerts",
    "brand.tagline": "Climate-aware navigation",
    "header.homeLabel": "AtmosPath home",
    "header.notifications": "Notifications",
    "header.signIn": "Sign in",
    "header.signOut": "Sign out",
    "header.emailSignIn": "Email sign in",
    "header.googleSignUp": "Continue with Google",
    "header.googleUnavailable":
      "Google sign-in is wired, but OAuth secrets are not configured for this deployment yet.",
    "header.authUnavailable": "Sign-in is available in the deployed preview.",
    "language.label": "Language",
    "language.en": "EN",
    "language.ko": "KR",
    "toast.loginFailed": "We could not complete sign-in.",
    "notFound.title": "This route is not on the map yet.",
    "notFound.detail": "Return home or open the live national risk map.",
    "notFound.home": "Go home",
    "notFound.map": "Open map",
    "home.eyebrow": "Nationwide mobility risk intelligence",
    "home.title": "Plan routes around weather risk",
    "home.subtitle":
      "Compare alternate routes with live hazard alerts and forecast signals.",
    "home.search": "Search cities, addresses, highways, or routes",
    "home.planRoute": "Plan route",
    "home.planRouteSubtitle": "Compare alternatives",
    "home.exploreRisk": "Explore risk",
    "home.exploreRiskSubtitle": "View map layers",
    "home.savedPlaces": "Saved places",
    "home.savedPlacesSubtitle": "Open watchlist",
    "home.nationalOutlook": "National risk outlook",
    "home.viewMap": "View full map",
    "home.activeHazards": "Active weather hazards",
    "home.viewAlerts": "View all alerts",
    "home.highestRisk": "Highest risk watch areas",
    "home.openDashboard": "Open dashboard",
    "dashboard.title": "National risk dashboard",
    "dashboard.subtitle": "Live weather points and route-impact alerts",
    "dashboard.manageSaved": "Manage saved places",
    "dashboard.planRoute": "Plan route",
    "saved.title": "Saved places",
    "saved.subtitle": "User-specific watchlist backed by DynamoDB",
    "saved.addFromMap": "Add from map",
    "saved.signInTitle": "Sign in to use your watchlist",
    "saved.signInDetail":
      "Saved places are private user data. We do not show fake records to public visitors.",
    "saved.signInAction": "Sign in",
    "saved.googleAction": "Continue with Google",
    "alerts.title": "Live alerts",
    "alerts.subtitle": "National Weather Service active hazard alerts",
    "alerts.severeOnly": "Severe only",
    "alerts.showAll": "Show all",
    "data.loading": "Connecting to live data",
    "data.partial": "Live data partially available",
    "data.unavailable": "Live data service unavailable",
    "data.loadingDetail": "Loading NOAA/NWS and platform API signals.",
    "data.unavailableDetail":
      "Unavailable values are hidden; no fabricated data is being substituted.",
  },
  ko: {
    "nav.home": "홈",
    "nav.map": "지도",
    "nav.dashboard": "대시보드",
    "nav.saved": "저장됨",
    "nav.alerts": "알림",
    "brand.tagline": "날씨 위험 기반 내비게이션",
    "header.homeLabel": "AtmosPath 홈",
    "header.notifications": "알림",
    "header.signIn": "로그인",
    "header.signOut": "로그아웃",
    "header.emailSignIn": "이메일 로그인",
    "header.googleSignUp": "Google로 계속하기",
    "header.googleUnavailable":
      "Google 로그인은 연결되어 있지만, 이 배포에는 아직 OAuth 시크릿이 설정되지 않았습니다.",
    "header.authUnavailable": "로그인은 배포된 프리뷰에서 사용할 수 있습니다.",
    "language.label": "언어",
    "language.en": "EN",
    "language.ko": "KR",
    "toast.loginFailed": "로그인을 완료하지 못했습니다.",
    "notFound.title": "아직 지도에 없는 경로입니다.",
    "notFound.detail": "홈으로 돌아가거나 실시간 전국 위험 지도를 여세요.",
    "notFound.home": "홈으로",
    "notFound.map": "지도 열기",
    "home.eyebrow": "전국 이동 위험 인텔리전스",
    "home.title": "날씨를 고려해 경로를 계획하세요",
    "home.subtitle": "실시간 위험 경보와 예보 신호로 대체 경로를 비교합니다.",
    "home.search": "도시, 주소, 고속도로, 경로 검색",
    "home.planRoute": "경로 계획",
    "home.planRouteSubtitle": "대체 경로 비교",
    "home.exploreRisk": "위험 탐색",
    "home.exploreRiskSubtitle": "지도 레이어 보기",
    "home.savedPlaces": "저장한 장소",
    "home.savedPlacesSubtitle": "관심 목록 열기",
    "home.nationalOutlook": "전국 위험 현황",
    "home.viewMap": "전체 지도 보기",
    "home.activeHazards": "활성 기상 위험",
    "home.viewAlerts": "전체 알림 보기",
    "home.highestRisk": "현재 고위험 모니터링 지역",
    "home.openDashboard": "대시보드 열기",
    "dashboard.title": "전국 위험 대시보드",
    "dashboard.subtitle": "실시간 날씨 지점과 이동 위험 경보",
    "dashboard.manageSaved": "저장한 장소 관리",
    "dashboard.planRoute": "경로 계획",
    "saved.title": "저장한 장소",
    "saved.subtitle": "사용자별 DynamoDB 기반 관심 목록",
    "saved.addFromMap": "지도에서 추가",
    "saved.signInTitle": "관심 목록을 사용하려면 로그인하세요",
    "saved.signInDetail":
      "저장한 장소는 사용자별 비공개 데이터입니다. 공개 방문자에게 가짜 기록을 보여주지 않습니다.",
    "saved.signInAction": "로그인",
    "saved.googleAction": "Google로 계속하기",
    "alerts.title": "실시간 알림",
    "alerts.subtitle": "National Weather Service 활성 위험 경보",
    "alerts.severeOnly": "심각한 알림만",
    "alerts.showAll": "전체 보기",
    "data.loading": "실시간 데이터 연결 중",
    "data.partial": "일부 실시간 데이터만 사용 가능",
    "data.unavailable": "실시간 데이터 서비스를 사용할 수 없음",
    "data.loadingDetail": "NOAA/NWS와 플랫폼 API를 불러오는 중입니다.",
    "data.unavailableDetail":
      "사용 불가 값은 숨기며, 가짜 데이터로 대체하지 않습니다.",
  },
} as const;

export type CopyKey = keyof typeof copy.en;

export interface I18nValue {
  language: Language;
  setLanguage: (language: Language) => void;
  t: (key: CopyKey) => string;
}

export const I18nContext = createContext<I18nValue | null>(null);

export function initialLanguage(): Language {
  if (typeof window === "undefined") {
    return "en";
  }

  const stored = window.localStorage.getItem(languageKey);
  if (stored === "ko" || stored === "en") {
    return stored;
  }

  return window.navigator.language.toLowerCase().startsWith("ko") ? "ko" : "en";
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) {
    throw new Error("useI18n must be used within I18nContext.Provider");
  }
  return value;
}
