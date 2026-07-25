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
      "These Terms of Service govern your access to and use of FreightScaler, a climate-aware route planning service operated by FreightScaler Labs (\"FreightScaler\", \"we\", or \"us\"). By creating an account or using the service, you agree to these terms. If you use FreightScaler on behalf of an organization, you confirm that you can bind that organization to these terms.",
    sections: [
      {
        heading: "Acceptance of Terms",
        paragraphs: [
          "By accessing the FreightScaler web application, APIs, or related services, you agree to be bound by these Terms and our Privacy Policy. If you do not agree, do not use the service. We may update these Terms from time to time; continued use after changes take effect constitutes acceptance of the revised Terms.",
        ],
      },
      {
        heading: "Description of the Service",
        paragraphs: [
          "FreightScaler provides map-based route planning for passenger cars, vans, and trucks across the United States. The service compares alternative routes using live and forecast weather signals, National Weather Service (NWS) hazard alerts, and derived risk scores, and lets you save routes and places for ongoing monitoring.",
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
          "FreightScaler combines public data from sources such as NOAA, the National Weather Service, and third-party map and weather providers with our own derived risk models. Upstream sources can be delayed, incomplete, or unavailable. When a source is unavailable, we hide the affected values rather than substituting fabricated data.",
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
          "FreightScaler includes an AI assistant that summarizes alerts and explains route tradeoffs. AI output is generated from models that can produce inaccurate or incomplete statements, even when citations are shown. Review the underlying sources before relying on any AI output for dispatch or safety decisions.",
          "Conversations with the assistant are processed by our AI providers under the data practices described in the Privacy Policy.",
        ],
      },
      {
        heading: "Plans, Quotas, and Billing",
        paragraphs: [
          "FreightScaler offers a free tier with daily quotas for route planning, place search, and saved items, as well as paid Pro and Team tiers with higher limits. Paid billing is not yet enabled; when it launches, prices will be published on the pricing page before any charge is made, and no account will be billed without explicit opt-in.",
          "Quotas reset daily. When you exceed a quota, the affected feature returns a clear limit notice instead of failing silently.",
        ],
      },
      {
        heading: "Intellectual Property",
        paragraphs: [
          "The service, including its software, design, risk models, and branding, is owned by FreightScaler and its licensors and protected by intellectual property laws. You receive a limited, non-exclusive, non-transferable license to use the service for its intended purpose.",
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
          "To the maximum extent permitted by law, FreightScaler and its suppliers will not be liable for indirect, incidental, special, consequential, or punitive damages, or for lost profits, revenues, or data, arising from your use of the service. Our total liability will not exceed the amounts you paid to us in the twelve months preceding the claim, or one hundred U.S. dollars if you have paid nothing.",
        ],
      },
      {
        heading: "Indemnification",
        paragraphs: [
          "You agree to indemnify and hold FreightScaler harmless from third-party claims arising out of your misuse of the service, your violation of these Terms, or your violation of applicable law.",
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
          "We will post any changes to these Terms on this page with an updated revision date. Material changes will be highlighted in the application before they take effect. Questions about these Terms can be sent to legal@freightscaler.app.",
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
      "This Privacy Policy explains what data FreightScaler collects, how it is used, and the choices available to you. The short version: we collect the minimum needed to plan and monitor routes, we do not sell personal data, and weather sources are public feeds that never receive your identity.",
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
          "FreightScaler uses browser local storage for the language setting, onboarding state, notification preferences, and session tokens. We do not use third-party advertising or tracking cookies. Clearing site data in your browser removes all locally stored preferences.",
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
          "You can export or delete your saved items in the app at any time, and you can clear local data from the Settings page. Depending on your region, you may have rights to access, correct, export, or erase personal data under regulations such as the GDPR or CCPA. Send requests to privacy@freightscaler.app and we will respond within 30 days.",
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
          "FreightScaler is not directed to children under 16, and we do not knowingly collect their data. If you believe a child has provided us personal data, contact us and we will delete it.",
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
          "Privacy questions: privacy@freightscaler.app. Legal questions: legal@freightscaler.app.",
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
          <span className="legal-eyebrow">{icon} FreightScaler</span>
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
