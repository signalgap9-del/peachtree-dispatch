import { ArrowLeft, Scale, ShieldCheck } from "lucide-react";
import type { ReactNode } from "react";

import type { Navigate } from "./App";
import { useI18n, type Language } from "./i18n";

type LegalSection = { heading: string; paragraphs: string[] };
type LegalDocument = { title: string; updated: string; intro: string; sections: LegalSection[] };

const TERMS: Record<Language, LegalDocument> = {
  en: {
    title: "Terms of Service",
    updated: "July 1, 2026",
    intro:
      "These Terms of Service govern your access to and use of AtmosPath, a climate-aware route planning service operated by AtmosPath Labs (\"AtmosPath\", \"we\", or \"us\"). By creating an account or using the service, you agree to these terms. If you use AtmosPath on behalf of an organization, you confirm that you can bind that organization to these terms.",
    sections: [
      {
        heading: "Acceptance of Terms",
        paragraphs: [
          "By accessing the AtmosPath web application, APIs, or related services, you agree to be bound by these Terms and our Privacy Policy. If you do not agree, do not use the service. We may update these Terms from time to time; continued use after changes take effect constitutes acceptance of the revised Terms.",
        ],
      },
      {
        heading: "Description of the Service",
        paragraphs: [
          "AtmosPath provides map-based route planning for passenger cars, vans, and trucks across the United States. The service compares alternative routes using live and forecast weather signals, National Weather Service (NWS) hazard alerts, and derived risk scores, and lets you save routes and places for ongoing monitoring.",
          "The service is provided for trip planning support. It does not replace official road closures, chain requirements, or evacuation orders issued by transportation authorities.",
        ],
      },
      {
        heading: "Accounts and Eligibility",
        paragraphs: [
          "You must be at least 16 years old to create an account. You are responsible for the accuracy of your account information and for all activity under your credentials. Notify us promptly if you believe your account has been compromised.",
          "Accounts are personal to you unless you are provisioned into a team workspace by your administrator. Workspace administrators can view and manage routes and places saved within their workspace.",
        ],
      },
      {
        heading: "Acceptable Use",
        paragraphs: [
          "You agree not to misuse the service, including attempting to gain unauthorized access to any portion of the service; scraping, reselling, or bulk-extracting data without written permission; interfering with rate limits or quotas; or using the service to plan transport of unlawful goods.",
          "We may suspend or terminate access that violates these rules, and we may throttle usage that degrades the service for other customers.",
        ],
      },
      {
        heading: "Weather, Alert, and Map Data",
        paragraphs: [
          "AtmosPath combines public data from sources such as NOAA, the National Weather Service, and third-party map and weather providers with our own derived risk models. Upstream sources can be delayed, incomplete, or unavailable. When a source is unavailable, we hide the affected values rather than substituting fabricated data.",
          "Risk scores are statistical estimates. They are not guarantees of actual road conditions, travel time, or safety.",
        ],
      },
      {
        heading: "No Professional or Emergency Advice",
        paragraphs: [
          "Route suggestions, risk scores, and AI-generated explanations are informational only. You remain responsible for driving decisions, load securement, hours-of-service compliance, and adherence to official guidance. In an emergency, follow instructions from local authorities, not the service.",
        ],
      },
      {
        heading: "Saved Routes, Places, and Monitoring",
        paragraphs: [
          "Saved routes and places are stored in your account and are subject to the limits of your plan. Monitoring checks saved routes against live hazard data on a periodic schedule; delivery of alerts depends on network availability and the notification settings on your device.",
          "You may delete saved items at any time. Deleting an item stops monitoring for it immediately.",
        ],
      },
      {
        heading: "AI Assistant",
        paragraphs: [
          "AtmosPath includes an AI assistant that summarizes alerts and explains route tradeoffs. AI output is generated from models that can produce inaccurate or incomplete statements, even when citations are shown. Review the underlying sources before relying on any AI output for dispatch or safety decisions.",
          "Conversations with the assistant are processed by our AI providers under the data practices described in the Privacy Policy.",
        ],
      },
      {
        heading: "Plans, Quotas, and Billing",
        paragraphs: [
          "AtmosPath offers a free tier with daily quotas for route planning, place search, and saved items, as well as paid Pro and Team tiers with higher limits. Paid billing is not yet enabled; when it launches, prices will be published on the pricing page before any charge is made, and no account will be billed without explicit opt-in.",
          "Quotas reset daily. When you exceed a quota, the affected feature returns a clear limit notice instead of failing silently.",
        ],
      },
      {
        heading: "Intellectual Property",
        paragraphs: [
          "The service, including its software, design, risk models, and branding, is owned by AtmosPath and its licensors and protected by intellectual property laws. You receive a limited, non-exclusive, non-transferable license to use the service for its intended purpose.",
          "Open-source components used by the service are governed by their respective licenses, which are honored in full.",
        ],
      },
      {
        heading: "Feedback",
        paragraphs: [
          "If you send us suggestions or feedback, you grant us a perpetual, royalty-free license to use them without obligation or attribution to you. Do not submit ideas you consider confidential.",
        ],
      },
      {
        heading: "Third-Party Services",
        paragraphs: [
          "The service relies on third-party infrastructure, including cloud hosting, map tiles, weather feeds, and AI providers. Your use of third-party services is also subject to their terms. We are not responsible for changes to or discontinuation of third-party services.",
        ],
      },
      {
        heading: "Suspension and Termination",
        paragraphs: [
          "You may close your account at any time. We may suspend or terminate accounts that breach these Terms, remain inactive for extended periods, or are used fraudulently. Provisions that by their nature should survive termination will do so.",
        ],
      },
      {
        heading: "Disclaimer of Warranties",
        paragraphs: [
          "The service is provided \"as is\" and \"as available\" without warranties of any kind, whether express or implied, including merchantability, fitness for a particular purpose, and non-infringement. We do not warrant that the service will be uninterrupted, error-free, or free of harmful components.",
        ],
      },
      {
        heading: "Limitation of Liability",
        paragraphs: [
          "To the maximum extent permitted by law, AtmosPath and its suppliers will not be liable for indirect, incidental, special, consequential, or punitive damages, or for lost profits, revenues, or data, arising from your use of the service. Our total liability will not exceed the amounts you paid to us in the twelve months preceding the claim, or one hundred U.S. dollars if you have paid nothing.",
        ],
      },
      {
        heading: "Indemnification",
        paragraphs: [
          "You agree to indemnify and hold AtmosPath harmless from third-party claims arising out of your misuse of the service, your violation of these Terms, or your violation of applicable law.",
        ],
      },
      {
        heading: "Governing Law and Venue",
        paragraphs: [
          "These Terms are governed by the laws of the State of Delaware, excluding conflict-of-law rules. Exclusive venue for disputes lies in the state and federal courts located in Delaware, and you consent to their jurisdiction.",
        ],
      },
      {
        heading: "Changes and Contact",
        paragraphs: [
          "We will post any changes to these Terms on this page with an updated revision date. Material changes will be highlighted in the application before they take effect. Questions about these Terms can be sent to legal@atmospath.app.",
        ],
      },
    ],
  },
  ko: {
    title: "이용약관",
    updated: "2026년 7월 1일",
    intro:
      "본 이용약관은 AtmosPath Labs(이하 \"AtmosPath\", \"당사\")가 운영하는 기후 인식 경로 계획 서비스 AtmosPath의 이용에 적용됩니다. 계정을 생성하거나 서비스를 이용하면 본 약관에 동의하게 됩니다. 조직을 대표해 이용하는 경우 해당 조직을 본 약관에 구속할 권한이 있음을 확인한 것으로 봅니다.",
    sections: [
      {
        heading: "약관의 수락",
        paragraphs: [
          "AtmosPath 웹 애플리케이션, API 또는 관련 서비스에 접근함으로써 본 이용약관과 개인정보처리방침에 동의하게 됩니다. 동의하지 않는 경우 서비스를 이용하지 마십시오. 당사는 약관을 수시로 개정할 수 있으며, 변경 사항이 효력을 발생한 후 계속 이용하면 개정된 약관에 동의한 것으로 봅니다.",
        ],
      },
      {
        heading: "서비스의 내용",
        paragraphs: [
          "AtmosPath는 미국 전역에서 승용차, 밴, 트럭을 위한 지도 기반 경로 계획을 제공합니다. 실시간 및 예보 기상 신호, National Weather Service(NWS) 위험 경보, 파생 위험 점수를 활용해 대안 경로를 비교하고, 경로와 장소를 저장해 지속적으로 모니터링할 수 있습니다.",
          "본 서비스는 여정 계획 지원용입니다. 교통 당국이 발표한 공식 도로 통제, 체인 장착 요구, 대피 명령을 대체하지 않습니다.",
        ],
      },
      {
        heading: "계정 및 이용 자격",
        paragraphs: [
          "계정 생성은 만 16세 이상만 가능합니다. 계정 정보의 정확성과 본인 자격 증명으로 이루어지는 모든 활동에 대한 책임은 회원에게 있습니다. 계정 침해가 의심되면 즉시 알려 주시기 바랍니다.",
          "관리자가 팀 워크스페이스에 추가하지 않는 한 계정은 개인 전용입니다. 워크스페이스 관리자는 해당 워크스페이스에 저장된 경로와 장소를 확인하고 관리할 수 있습니다.",
        ],
      },
      {
        heading: "허용되는 이용",
        paragraphs: [
          "서비스의 무단 접근 시도, 서면 허가 없는 스크래핑·재판매·대량 추출, 속도 제한 우회, 불법 화물 운송 계획 등 서비스의 오용에 동의하지 않습니다.",
          "당사는 이러한 규칙을 위반하는 접근을 정지하거나 해지할 수 있으며, 다른 이용자에게 피해를 주는 사용을 제한할 수 있습니다.",
        ],
      },
      {
        heading: "기상·경보·지도 데이터",
        paragraphs: [
          "AtmosPath는 NOAA, National Weather Service 등 공공 데이터와 타사 지도·기상 제공업체 데이터를 자체 위험 모델과 결합합니다. 상위 데이터는 지연되거나 불완전하거나 제공되지 않을 수 있습니다. 데이터가 제공되지 않으면 당사는 값을 숨길 뿐 가짜 데이터로 대체하지 않습니다.",
          "위험 점수는 통계적 추정치이며 실제 도로 상태, 소요 시간, 안전에 대한 보장이 아닙니다.",
        ],
      },
      {
        heading: "전문적·비상 조언 아님",
        paragraphs: [
          "경로 제안, 위험 점수, AI 생성 설명은 정보 제공 목적입니다. 운전 판단, 화물 고정, 운행 시간 규정 준수, 공식 지침 준수에 대한 책임은 이용자에게 있습니다. 비상시에는 서비스가 아닌 지역 당국의 지시를 따르십시오.",
        ],
      },
      {
        heading: "저장 경로·장소 및 모니터링",
        paragraphs: [
          "저장된 경로와 장소는 계정에 보관되며 플랜의 한도가 적용됩니다. 모니터링은 주기적으로 저장 경로를 실시간 위험 데이터와 대조하며, 경보 수신은 네트워크 상태와 기기의 알림 설정에 따라 달라질 수 있습니다.",
          "저장 항목은 언제든 삭제할 수 있으며, 삭제 즉시 해당 항목의 모니터링이 중단됩니다.",
        ],
      },
      {
        heading: "AI 어시스턴트",
        paragraphs: [
          "AtmosPath에는 경보를 요약하고 경로 상충 요소를 설명하는 AI 어시스턴트가 포함되어 있습니다. AI 출력은 출처가 표시되더라도 부정확하거나 불완전할 수 있는 모델로 생성됩니다. 배차나 안전 결정에 의존하기 전에 원본 출처를 확인하십시오.",
          "어시스턴트 대화는 개인정보처리방침에 명시된 데이터 처리 방침에 따라 AI 제공업체에서 처리됩니다.",
        ],
      },
      {
        heading: "플랜, 한도 및 결제",
        paragraphs: [
          "AtmosPath는 경로 계획, 장소 검색, 저장 항목에 일일 한도가 있는 무료 플랜과 더 높은 한도의 유료 Pro·Team 플랜을 제공합니다. 유료 결제는 아직 활성화되지 않았습니다. 결제가 시작되면 요금이 청구되기 전에 가격 페이지에 요금이 공개되며, 명시적 동의 없이 청구되지 않습니다.",
          "한도는 매일 초기화됩니다. 한도를 초과하면 해당 기능은 조용히 실패하지 않고 명확한 한도 안내를 반환합니다.",
        ],
      },
      {
        heading: "지식재산권",
        paragraphs: [
          "소프트웨어, 디자인, 위험 모델, 브랜드를 포함한 서비스는 AtmosPath와 라이선스 제공자의 소유이며 지식재산권법의 보호를 받습니다. 회원은 서비스의 본래 목적에 한해 제한적, 비독점적, 양도 불가한 이용 허락을 받습니다.",
          "서비스에 사용된 오픈소스 구성 요소는 각 라이선스의 적용을 받으며, 당사는 이를 준수합니다.",
        ],
      },
      {
        heading: "피드백",
        paragraphs: [
          "제안이나 피드백을 보내면 당사는 이를 의무나 표시 없이 영구적으로 무상 사용할 수 있는 권리를 부여받습니다. 기밀로 간주하는 아이디어는 제출하지 마십시오.",
        ],
      },
      {
        heading: "타사 서비스",
        paragraphs: [
          "서비스는 클라우드 호스팅, 지도 타일, 기상 피드, AI 제공업체 등 타사 인프라에 의존합니다. 타사 서비스 이용에는 해당 약관도 적용됩니다. 당사는 타사 서비스의 변경이나 중단에 책임지지 않습니다.",
        ],
      },
      {
        heading: "이용 정지 및 해지",
        paragraphs: [
          "언제든 계정을 해지할 수 있습니다. 당사는 약관을 위반하거나, 장기간 비활성 상태이거나, 부정하게 이용되는 계정을 정지 또는 해지할 수 있습니다. 성격상 존속해야 하는 조항은 해지 후에도 유효합니다.",
        ],
      },
      {
        heading: "보증의 부인",
        paragraphs: [
          "서비스는 상품성, 특정 목적 적합성, 비침해성에 대한 묵시적 보증을 포함해 어떠한 종류의 보증도 없이 \"있는 그대로\" 제공됩니다. 당사는 서비스의 중단 없음, 오류 없음, 유해 요소 없음을 보증하지 않습니다.",
        ],
      },
      {
        heading: "책임의 제한",
        paragraphs: [
          "법률이 허용하는 최대 한도 내에서 AtmosPath와 공급자는 서비스 이용으로 인한 간접적, 부수적, 특별, 결과적, 징벌적 손해 또는 이익·매출·데이터 손실에 대해 책임지지 않습니다. 총 책임 한도는 청구 전 12개월 동안 지불한 금액이며, 지불한 금액이 없는 경우 100달러입니다.",
        ],
      },
      {
        heading: "면책",
        paragraphs: [
          "회원은 서비스 오용, 약관 위반, 관련 법령 위반으로 인한 제3자의 청구로부터 AtmosPath를 면책하고 피해를 보상하는 데 동의합니다.",
        ],
      },
      {
        heading: "준거법 및 관할",
        paragraphs: [
          "본 약관은 법률 충돌 규칙을 제외하고 델라웨어주 법의 적용을 받습니다. 분쟁에 대한 전속 관할은 델라웨어주 소재 주 법원 및 연방 법원이며, 회원은 해당 관할에 동의합니다.",
        ],
      },
      {
        heading: "변경 및 문의",
        paragraphs: [
          "약관 변경 사항은 개정일과 함께 이 페이지에 게시됩니다. 중대한 변경은 효력 발생 전 애플리케이션에서 강조 표시됩니다. 문의는 legal@atmospath.app으로 보내 주십시오.",
        ],
      },
    ],
  },
};

const PRIVACY: Record<Language, LegalDocument> = {
  en: {
    title: "Privacy Policy",
    updated: "July 1, 2026",
    intro:
      "This Privacy Policy explains what data AtmosPath collects, how it is used, and the choices available to you. The short version: we collect the minimum needed to plan and monitor routes, we do not sell personal data, and weather sources are public feeds that never receive your identity.",
    sections: [
      {
        heading: "Information We Collect",
        paragraphs: [
          "Account data: when you sign in, we store your email address, a stable user identifier, and workspace membership. Saved items: routes and places you explicitly save, including their names, endpoints, and monitoring settings. Usage data: searches, route calculations, and feature usage counts used to enforce plan quotas and improve the product. Local data: language preference, onboarding state, and notification preferences stored in your browser's local storage.",
        ],
      },
      {
        heading: "How We Use Information",
        paragraphs: [
          "We use your data to operate the service: calculating routes, monitoring saved routes against live hazards, enforcing quotas, and responding to support requests. Aggregate, de-identified usage statistics help us improve risk models and performance. We do not use your data for third-party advertising.",
        ],
      },
      {
        heading: "Weather and Alert Sources",
        paragraphs: [
          "Risk features rely on public sources such as NOAA and the National Weather Service, plus licensed weather and map providers. When we query these sources, requests contain only the geographic coordinates or place names needed for the lookup - never your account identity.",
        ],
      },
      {
        heading: "Cookies and Local Storage",
        paragraphs: [
          "AtmosPath uses browser local storage for the language setting, onboarding state, notification preferences, and session tokens. We do not use third-party advertising or tracking cookies. Clearing site data in your browser removes all locally stored preferences.",
        ],
      },
      {
        heading: "Third-Party Processors",
        paragraphs: [
          "We use a small set of processors: cloud infrastructure (AWS) for hosting and databases, map and weather providers for tiles and forecasts, and AI providers to power the assistant and alert summaries. Each processor is bound by contracts limiting use of the data to providing their service. AI prompts may include route endpoints and alert text, but not your email address.",
        ],
      },
      {
        heading: "Data Retention",
        paragraphs: [
          "Saved routes and places are kept until you delete them or close your account. Usage logs are retained for up to 90 days, then deleted or aggregated. Risk history for saved routes is kept according to your plan's history window.",
        ],
      },
      {
        heading: "Your Choices and Rights",
        paragraphs: [
          "You can export or delete your saved items in the app at any time, and you can clear local data from the Settings page. Depending on your region, you may have rights to access, correct, export, or erase personal data under regulations such as the GDPR or CCPA. Send requests to privacy@atmospath.app and we will respond within 30 days.",
        ],
      },
      {
        heading: "Data Security",
        paragraphs: [
          "All traffic is encrypted in transit. Access to production data is restricted to a small number of engineers under least-privilege access controls, and authentication relies on short-lived credentials rather than long-lived keys.",
        ],
      },
      {
        heading: "Children's Privacy",
        paragraphs: [
          "AtmosPath is not directed to children under 16, and we do not knowingly collect their data. If you believe a child has provided us personal data, contact us and we will delete it.",
        ],
      },
      {
        heading: "International Transfers",
        paragraphs: [
          "The service is operated from the United States. If you access it from elsewhere, your data will be transferred to and processed in the U.S. under safeguards appropriate to the sensitivity of the data.",
        ],
      },
      {
        heading: "Changes to This Policy",
        paragraphs: [
          "We will update this page when our practices change and revise the date at the top. Material changes will be announced in the application before they take effect.",
        ],
      },
      {
        heading: "Contact",
        paragraphs: [
          "Privacy questions: privacy@atmospath.app. Legal questions: legal@atmospath.app.",
        ],
      },
    ],
  },
  ko: {
    title: "개인정보처리방침",
    updated: "2026년 7월 1일",
    intro:
      "본 개인정보처리방침은 AtmosPath가 수집하는 데이터, 이용 방법, 이용자의 선택권을 설명합니다. 요약하자면: 경로 계획과 모니터링에 필요한 최소한의 데이터만 수집하며, 개인정보를 판매하지 않고, 기상 데이터는 신원 정보가 전달되지 않는 공개 피드를 사용합니다.",
    sections: [
      {
        heading: "수집하는 정보",
        paragraphs: [
          "계정 데이터: 로그인 시 이메일 주소, 안정적 사용자 식별자, 워크스페이스 소속을 저장합니다. 저장 항목: 이름, 출발·도착지, 모니터링 설정 등 명시적으로 저장한 경로와 장소. 이용 데이터: 플랜 한도 적용과 제품 개선을 위한 검색, 경로 계산, 기능 사용량. 로컬 데이터: 브라우저 로컬 저장소에 보관되는 언어 설정, 온보딩 상태, 알림 환경설정.",
        ],
      },
      {
        heading: "정보 이용 방법",
        paragraphs: [
          "데이터는 서비스 운영에 사용합니다: 경로 계산, 저장 경로의 실시간 위험 모니터링, 한도 적용, 고객 지원 응대. 비식별화된 집계 통계는 위험 모델과 성능 개선에 활용합니다. 제3자 광고에는 데이터를 사용하지 않습니다.",
        ],
      },
      {
        heading: "기상 및 경보 출처",
        paragraphs: [
          "위험 기능은 NOAA, National Weather Service 등 공개 출처와 라이선스 기상·지도 제공업체에 의존합니다. 조회 요청에는 조회에 필요한 지리적 좌표나 장소명만 포함되며 계정 신원 정보는 포함되지 않습니다.",
        ],
      },
      {
        heading: "쿠키 및 로컬 저장소",
        paragraphs: [
          "AtmosPath는 언어 설정, 온보딩 상태, 알림 환경설정, 세션 토큰에 브라우저 로컬 저장소를 사용합니다. 제3자 광고·추적 쿠키는 사용하지 않습니다. 브라우저에서 사이트 데이터를 삭제하면 로컬에 저장된 모든 환경설정이 제거됩니다.",
        ],
      },
      {
        heading: "제3자 처리자",
        paragraphs: [
          "소수의 처리자를 사용합니다: 호스팅과 데이터베이스를 위한 클라우드 인프라(AWS), 타일과 예보를 위한 지도·기상 제공업체, 어시스턴트와 경보 요약을 위한 AI 제공업체. 각 처리자는 계약에 따라 데이터 이용이 서비스 제공 목적으로 제한됩니다. AI 프롬프트에는 경로 출발·도착지와 경보 텍스트가 포함될 수 있지만 이메일 주소는 포함되지 않습니다.",
        ],
      },
      {
        heading: "데이터 보관",
        paragraphs: [
          "저장된 경로와 장소는 삭제하거나 계정을 해지할 때까지 보관됩니다. 이용 로그는 최대 90일 보관 후 삭제 또는 집계됩니다. 저장 경로의 위험 이력은 플랜의 이력 보관 기간에 따라 유지됩니다.",
        ],
      },
      {
        heading: "이용자의 선택과 권리",
        paragraphs: [
          "앱에서 저장 항목을 언제든 내보내거나 삭제할 수 있으며, 설정 페이지에서 로컬 데이터를 삭제할 수 있습니다. 지역에 따라 GDPR, CCPA 등 규정에 따라 개인정보의 열람, 정정, 내보내기, 삭제 권리를 가질 수 있습니다. privacy@atmospath.app으로 요청하시면 30일 이내에 응답합니다.",
        ],
      },
      {
        heading: "데이터 보안",
        paragraphs: [
          "모든 트래픽은 전송 구간에서 암호화됩니다. 운영 데이터 접근은 최소 권한 통제 하의 소수 엔지니어로 제한되며, 인증은 장기 키가 아닌 단기 자격 증명을 사용합니다.",
        ],
      },
      {
        heading: "아동 개인정보",
        paragraphs: [
          "AtmosPath는 만 16세 미만 아동을 대상으로 하지 않으며, 아동의 데이터를 의도적으로 수집하지 않습니다. 아동이 개인정보를 제공한 것으로 확인되면 삭제합니다.",
        ],
      },
      {
        heading: "국제 이전",
        paragraphs: [
          "서비스는 미국에서 운영됩니다. 다른 지역에서 접근하는 경우 데이터는 데이터의 민감도에 맞는 보호 조치 하에 미국으로 이전되어 처리됩니다.",
        ],
      },
      {
        heading: "방침의 변경",
        paragraphs: [
          "처리 관행이 변경되면 이 페이지를 갱신하고 상단의 날짜를 개정합니다. 중대한 변경은 효력 발생 전 애플리케이션에서 안내합니다.",
        ],
      },
      {
        heading: "문의",
        paragraphs: [
          "개인정보 문의: privacy@atmospath.app. 법률 문의: legal@atmospath.app.",
        ],
      },
    ],
  },
};

function LegalPage({ navigate, doc, icon }: { navigate: Navigate; doc: LegalDocument; icon: ReactNode }) {
  const { t } = useI18n();
  return (
    <main className="page-shell legal-page">
      <button className="back-link legal-back" onClick={() => navigate("/")}>
        <ArrowLeft size={14} /> {t("legal.back")}
      </button>
      <article className="surface legal-document">
        <header className="legal-head">
          <span className="legal-eyebrow">{icon} AtmosPath</span>
          <h1>{doc.title}</h1>
          <time className="legal-updated">{t("legal.lastUpdated")}: {doc.updated}</time>
        </header>
        <p className="legal-intro">{doc.intro}</p>
        {doc.sections.map((section, index) => (
          <section key={section.heading} className="legal-section">
            <h2>{index + 1}. {section.heading}</h2>
            {section.paragraphs.map((paragraph) => (
              <p key={paragraph.slice(0, 32)}>{paragraph}</p>
            ))}
          </section>
        ))}
      </article>
    </main>
  );
}

export function TermsPage({ navigate }: { navigate: Navigate }) {
  const { language } = useI18n();
  return <LegalPage navigate={navigate} doc={TERMS[language]} icon={<Scale size={14} />} />;
}

export function PrivacyPage({ navigate }: { navigate: Navigate }) {
  const { language } = useI18n();
  return <LegalPage navigate={navigate} doc={PRIVACY[language]} icon={<ShieldCheck size={14} />} />;
}
