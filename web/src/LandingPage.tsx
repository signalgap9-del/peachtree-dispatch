import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BellRing,
  Bookmark,
  Car,
  Check,
  Gauge,
  MapPin,
  Navigation2,
  Radar,
  Radio,
  Scale,
  Search,
  ShieldCheck,
  Sparkles,
  Timer,
  TriangleAlert,
  Truck,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";

import { useI18n } from "./i18n";
import { LanguageToggle } from "./LanguageToggle";

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function useInView<T extends HTMLElement>(threshold = 0.18) {
  const ref = useRef<T | null>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    if (typeof IntersectionObserver === "undefined" || prefersReducedMotion) {
      setInView(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setInView(true);
            observer.disconnect();
          }
        }
      },
      { threshold, rootMargin: "0px 0px -6% 0px" },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [threshold]);
  return { ref, inView };
}

function Reveal({ children, delay = 0, className = "" }: { children: ReactNode; delay?: number; className?: string }) {
  const { ref, inView } = useInView<HTMLDivElement>();
  return (
    <div
      ref={ref}
      className={`lp-reveal${inView ? " is-in" : ""}${className ? ` ${className}` : ""}`}
      style={{ transitionDelay: `${delay}ms` }}
    >
      {children}
    </div>
  );
}

function useCountBetween(from: number, to: number, start: boolean, duration = 1500) {
  const [value, setValue] = useState(from);
  useEffect(() => {
    if (!start) return;
    if (prefersReducedMotion) {
      setValue(to);
      return;
    }
    let frame = 0;
    const startedAt = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      setValue(Math.round(from + (to - from) * eased));
      if (progress < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [start, from, to, duration]);
  return value;
}

function Wordmark() {
  const { t } = useI18n();
  return (
    <span className="lp-wordmark">
      <span className="peach-mark"><i /><b /></span>
      <span><strong>AtmosPath</strong><small>{t("brand.tagline")}</small></span>
    </span>
  );
}

function LandingHeader() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  const anchors = [
    { href: "#how", label: t("landing.nav.how") },
    { href: "#compare", label: t("landing.nav.compare") },
    { href: "#features", label: t("landing.nav.features") },
    { href: "#pricing", label: t("landing.nav.pricing") },
  ];
  return (
    <header className={`lp-header${scrolled ? " scrolled" : ""}`}>
      <div className="lp-container lp-header-inner">
        <a className="lp-wordmark-link" href="#top"><Wordmark /></a>
        <nav className="lp-nav" aria-label="AtmosPath landing">
          {anchors.map((anchor) => (
            <a key={anchor.href} href={anchor.href}>{anchor.label}</a>
          ))}
        </nav>
        <div className="lp-header-actions">
          <LanguageToggle />
          <button className="lp-btn lp-btn-ghost" onClick={() => navigate("/app")}>{t("landing.signIn")}</button>
          <button className="lp-btn lp-btn-primary lp-btn-sm" onClick={() => navigate("/app/map")}>
            {t("landing.openApp")} <ArrowUpRight size={15} />
          </button>
        </div>
      </div>
    </header>
  );
}

function HeroSection() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { ref, inView } = useInView<HTMLDivElement>(0.3);
  const alternatives = useCountBetween(0, 3, inView, 900);
  const riskScore = useCountBetween(58, 15, inView, 1800);
  const delayAvoided = useCountBetween(0, 628, inView, 1800);
  return (
    <section className="lp-hero" id="top" aria-labelledby="lp-hero-title">
      <svg className="lp-hero-routes" viewBox="0 0 1440 620" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        <path className="lp-route-line lp-route-line-a" d="M-60 468 C 240 430, 430 296, 706 296 S 1180 186, 1520 148" />
        <path className="lp-route-line lp-route-line-b" d="M-60 544 C 300 522, 520 398, 822 378 S 1240 296, 1520 258" />
        <circle className="lp-route-dot" cx="706" cy="296" r="5" />
        <circle className="lp-route-dot lp-route-dot-b" cx="822" cy="378" r="5" />
      </svg>
      <div className="lp-container lp-hero-grid">
        <div className="lp-hero-copy">
          <span className="lp-live-chip"><i />{t("landing.liveChip")}</span>
          <h1 id="lp-hero-title">
            {t("landing.hero.titlePre")}
            <em>{t("landing.hero.titleEm")}</em>
            {t("landing.hero.titlePost")}
          </h1>
          <p className="lp-hero-sub">{t("landing.hero.subtitle")}</p>
          <div className="lp-hero-cta">
            <button className="lp-btn lp-btn-primary" onClick={() => navigate("/app/map")}>
              {t("landing.hero.ctaPrimary")} <ArrowRight size={16} />
            </button>
            <a className="lp-btn lp-btn-secondary" href="#how">{t("landing.hero.ctaSecondary")}</a>
          </div>
          <div className="lp-hero-metrics" ref={ref}>
            <div>
              <strong className="lp-mono">{alternatives}</strong>
              <span>{t("landing.hero.metricPlans")}</span>
            </div>
            <div>
              <strong className="lp-mono lp-metric-risk">
                58 <ArrowRight size={15} aria-hidden="true" /> <b>{riskScore}</b>
              </strong>
              <span>{t("landing.hero.metricRisk")}</span>
            </div>
            <div>
              <strong className="lp-mono">{delayAvoided}</strong>
              <span>{t("landing.hero.metricDelay")}</span>
            </div>
          </div>
        </div>
        <div className="lp-hero-visual">
          <div className="lp-frame">
            <div className="lp-frame-bar">
              <span className="lp-frame-dots" aria-hidden="true"><i /><i /><i /></span>
              <span className="lp-frame-url lp-mono">{t("landing.hero.frameRoute")}</span>
              <span className="lp-frame-live lp-mono"><i />{t("landing.hero.frameLive")}</span>
            </div>
            <img
              src="/images/map-route-live.png"
              alt={t("landing.hero.frameAlt")}
              width={1600}
              height={900}
              fetchPriority="high"
              decoding="async"
            />
          </div>
          <div className="lp-float lp-float-selected">
            <ShieldCheck size={18} aria-hidden="true" />
            <span>
              <strong>{t("landing.hero.floatSelected")}</strong>
              <small className="lp-mono">{t("landing.hero.floatSelectedDetail")}</small>
            </span>
          </div>
          <div className="lp-float lp-float-alerts">
            <TriangleAlert size={15} aria-hidden="true" />
            <span>{t("landing.hero.floatAlerts")}</span>
          </div>
          <div className="lp-float lp-float-avoid">
            <Timer size={14} aria-hidden="true" />
            <span className="lp-mono">{t("landing.hero.floatAvoid")}</span>
          </div>
        </div>
      </div>
    </section>
  );
}

function HowSection() {
  const { t } = useI18n();
  const steps = [
    {
      num: "01",
      icon: Navigation2,
      title: t("landing.how.step1.title"),
      body: t("landing.how.step1.body"),
      visual: (
        <div className="lp-step-visual lp-step-plan" aria-hidden="true">
          <span className="lp-step-search"><Search size={14} /> {t("landing.how.step1.search")}</span>
          <span className="lp-step-modes">
            <i title="Car"><Car size={13} /></i>
            <i className="active" title="Truck"><Truck size={13} /></i>
          </span>
        </div>
      ),
    },
    {
      num: "02",
      icon: Scale,
      title: t("landing.how.step2.title"),
      body: t("landing.how.step2.body"),
      visual: (
        <div className="lp-step-visual lp-step-compare" aria-hidden="true">
          <span className="lp-mini-route">
            <i className="lp-mini-bar lp-mini-bar-amber" style={{ width: "82%" }} />
            <em className="lp-mono">58:24 · risk 58</em>
          </span>
          <span className="lp-mini-route">
            <i className="lp-mini-bar lp-mini-bar-green" style={{ width: "100%" }} />
            <em className="lp-mono">61:02 · risk 15</em>
          </span>
        </div>
      ),
    },
    {
      num: "03",
      icon: Radar,
      title: t("landing.how.step3.title"),
      body: t("landing.how.step3.body"),
      visual: (
        <div className="lp-step-visual lp-step-monitor" aria-hidden="true">
          <span className="lp-mini-alert">
            <i />
            <span><strong>{t("landing.how.step3.alert")}</strong><small className="lp-mono">12:41Z · NWS</small></span>
          </span>
          <span className="lp-mini-action">{t("landing.how.step3.action")}</span>
        </div>
      ),
    },
  ];
  return (
    <section className="lp-section lp-how" id="how" aria-labelledby="lp-how-title">
      <div className="lp-container lp-how-grid">
        <Reveal className="lp-how-intro">
          <span className="lp-eyebrow">{t("landing.how.eyebrow")}</span>
          <h2 id="lp-how-title">{t("landing.how.title")}</h2>
          <p>{t("landing.how.subtitle")}</p>
        </Reveal>
        <ol className="lp-steps">
          {steps.map((step, index) => (
            <li className="lp-step" key={step.num}>
              <Reveal className="lp-step-inner" delay={index * 130}>
                <span className="lp-step-node lp-mono">{step.num}</span>
                <div className="lp-step-body">
                  <span className="lp-step-icon"><step.icon size={16} /></span>
                  <h3>{step.title}</h3>
                  <p>{step.body}</p>
                  {step.visual}
                </div>
              </Reveal>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

const FASTEST_SEGMENTS = [
  { tone: "green", grow: 20 },
  { tone: "amber", grow: 13 },
  { tone: "red", grow: 17 },
  { tone: "amber", grow: 22 },
  { tone: "green", grow: 18.6 },
];

const LOWER_RISK_SEGMENTS = [
  { tone: "green", grow: 34 },
  { tone: "amber", grow: 9 },
  { tone: "green", grow: 41 },
  { tone: "amber", grow: 6 },
  { tone: "green", grow: 10 },
];

function RouteSegments({ segments }: { segments: typeof FASTEST_SEGMENTS }) {
  return (
    <>
      {segments.map((segment, index) => (
        <i key={index} className={`lp-seg lp-seg-${segment.tone}`} style={{ flexGrow: segment.grow }} />
      ))}
    </>
  );
}

function CompareSection() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { ref, inView } = useInView<HTMLDivElement>(0.25);
  return (
    <section className="lp-section lp-compare" id="compare" aria-labelledby="lp-compare-title">
      <div className="lp-container">
        <Reveal className="lp-section-head">
          <span className="lp-eyebrow">{t("landing.compare.eyebrow")}</span>
          <h2 id="lp-compare-title">{t("landing.compare.title")}</h2>
          <p>{t("landing.compare.subtitle")}</p>
        </Reveal>
        <Reveal delay={120}>
          <div className="lp-compare-panel" ref={ref}>
            <div className="lp-compare-head">
              <span className="lp-compare-od">
                <MapPin size={16} aria-hidden="true" />
                <strong>{t("landing.compare.origin")}</strong>
                <ArrowRight size={14} aria-hidden="true" />
                <strong>{t("landing.compare.destination")}</strong>
              </span>
              <span className="lp-compare-depart lp-mono">{t("landing.compare.depart")}</span>
            </div>

            <div className="lp-route-row">
              <div className="lp-route-labels">
                <span className="lp-route-tag lp-tag-amber">{t("landing.compare.fastest")}</span>
                <strong>{t("landing.compare.fastestRoute")}</strong>
                <span className="lp-risk-pill lp-risk-amber lp-mono">{t("landing.compare.riskLabel")} 58</span>
              </div>
              <div className="lp-bar-track">
                <div className="lp-bar-fill" style={{ width: inView ? "90.6%" : "0%" }}>
                  <RouteSegments segments={FASTEST_SEGMENTS} />
                </div>
              </div>
              <div className="lp-route-meta">
                <span className="lp-mono">3,658 mi · 58 hr 24 min</span>
                <span className="lp-route-note lp-note-amber">
                  <TriangleAlert size={13} aria-hidden="true" /> {t("landing.compare.delayFastest")}
                </span>
              </div>
            </div>

            <div className="lp-route-row lp-route-winner">
              <div className="lp-route-labels">
                <span className="lp-route-tag lp-tag-green">{t("landing.compare.recommended")}</span>
                <strong>{t("landing.compare.recommendedRoute")}</strong>
                <em className="lp-recommended-badge"><ShieldCheck size={12} aria-hidden="true" /> {t("landing.compare.badge")}</em>
                <span className="lp-risk-pill lp-risk-green lp-mono">{t("landing.compare.riskLabel")} 15</span>
              </div>
              <div className="lp-bar-track">
                <div className="lp-bar-fill" style={{ width: inView ? "100%" : "0%" }}>
                  <RouteSegments segments={LOWER_RISK_SEGMENTS} />
                </div>
              </div>
              <div className="lp-route-meta">
                <span className="lp-mono">4,039 mi · 61 hr 02 min</span>
                <span className="lp-route-note lp-note-green">
                  <ShieldCheck size={13} aria-hidden="true" /> {t("landing.compare.delayLower")}
                </span>
              </div>
            </div>

            <div className="lp-compare-why">
              <strong>{t("landing.compare.whyTitle")}</strong>
              <div className="lp-why-facts">
                <span><Check size={13} aria-hidden="true" /> {t("landing.compare.why1")}</span>
                <span><Check size={13} aria-hidden="true" /> {t("landing.compare.why2")}</span>
                <span><Check size={13} aria-hidden="true" /> {t("landing.compare.why3")}</span>
              </div>
              <button className="lp-btn lp-btn-primary" onClick={() => navigate("/app/directions")}>
                {t("landing.compare.openMap")} <ArrowRight size={15} />
              </button>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function FeaturesSection() {
  const { t } = useI18n();
  return (
    <section className="lp-section lp-features" id="features" aria-labelledby="lp-features-title">
      <div className="lp-container">
        <Reveal className="lp-section-head">
          <span className="lp-eyebrow">{t("landing.features.eyebrow")}</span>
          <h2 id="lp-features-title">{t("landing.features.title")}</h2>
          <p>{t("landing.features.subtitle")}</p>
        </Reveal>
        <div className="lp-bento">
          <Reveal className="lp-cell lp-cell-alerts">
            <header>
              <span className="lp-cell-icon lp-icon-red"><Radio size={16} /></span>
              <h3>{t("landing.features.alerts.title")}</h3>
            </header>
            <p>{t("landing.features.alerts.body")}</p>
            <div className="lp-alert-feed" aria-hidden="true">
              <span className="lp-alert-item">
                <i className="lp-sev-red" />
                <span><strong>Tornado Watch</strong><small>Tillman, OK</small></span>
                <em className="lp-mono">12:41Z</em>
              </span>
              <span className="lp-alert-item">
                <i className="lp-sev-red" />
                <span><strong>Flash Flood Warning</strong><small>Miami-Dade, FL</small></span>
                <em className="lp-mono">12:36Z</em>
              </span>
              <span className="lp-alert-item">
                <i className="lp-sev-amber" />
                <span><strong>Winter Storm Advisory</strong><small>Flagstaff, AZ</small></span>
                <em className="lp-mono">11:58Z</em>
              </span>
            </div>
            <footer className="lp-cell-foot lp-mono"><i className="lp-live-dot" />{t("landing.features.alerts.updated")}</footer>
          </Reveal>

          <Reveal className="lp-cell lp-cell-ai" delay={90}>
            <header>
              <span className="lp-cell-icon lp-icon-blue"><Sparkles size={16} /></span>
              <h3>{t("landing.features.ai.title")}</h3>
            </header>
            <p>{t("landing.features.ai.body")}</p>
            <div className="lp-ai-snippet" aria-hidden="true">
              <span className="lp-bubble lp-bubble-ai lp-bubble-mini">{t("landing.features.ai.quote")}</span>
              <span className="lp-src-row lp-mono">NWS · OSRM · FMCSA</span>
            </div>
          </Reveal>

          <Reveal className="lp-cell lp-cell-saved" delay={160}>
            <header>
              <span className="lp-cell-icon lp-icon-green"><Bookmark size={16} /></span>
              <h3>{t("landing.features.saved.title")}</h3>
            </header>
            <p>{t("landing.features.saved.body")}</p>
            <div className="lp-saved-chip" aria-hidden="true">
              <Bookmark size={13} />
              <span className="lp-mono">SEA → MIA · I-40 E</span>
              <em className="lp-risk-pill lp-risk-green lp-mono">15</em>
            </div>
          </Reveal>

          <Reveal className="lp-cell lp-cell-proactive" delay={0}>
            <header>
              <span className="lp-cell-icon lp-icon-amber"><BellRing size={16} /></span>
              <h3>{t("landing.features.proactive.title")}</h3>
            </header>
            <p>{t("landing.features.proactive.body")}</p>
            <div className="lp-proactive-mock" aria-hidden="true">
              <span className="lp-mono">{t("landing.features.proactive.mock")}</span>
              <em>{t("landing.features.proactive.action")}</em>
            </div>
          </Reveal>

          <Reveal className="lp-cell lp-cell-quotas" delay={90}>
            <header>
              <span className="lp-cell-icon lp-icon-blue"><Gauge size={16} /></span>
              <h3>{t("landing.features.quotas.title")}</h3>
            </header>
            <p>{t("landing.features.quotas.body")}</p>
            <div className="lp-quota" aria-hidden="true">
              <div className="lp-quota-meter"><i style={{ width: "42.7%" }} /></div>
              <span className="lp-mono">{t("landing.features.quotas.meter")}</span>
              <small className="lp-mono">{t("landing.features.quotas.reset")}</small>
            </div>
          </Reveal>

          <Reveal className="lp-cell lp-cell-status" delay={160}>
            <header>
              <span className="lp-cell-icon lp-icon-green"><Activity size={16} /></span>
              <h3>{t("landing.features.status.title")}</h3>
            </header>
            <p>{t("landing.features.status.body")}</p>
            <div className="lp-status-rows" aria-hidden="true">
              <span className="lp-status-all"><i className="lp-live-dot" />{t("landing.features.status.all")}</span>
              <span className="lp-status-line">API<em className="lp-mono">99.98%</em></span>
              <span className="lp-status-line">Alert stream<em className="lp-mono">100%</em></span>
              <span className="lp-status-line">Map tiles<em className="lp-mono">99.99%</em></span>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

function AiSection() {
  const { t } = useI18n();
  const navigate = useNavigate();
  return (
    <section className="lp-section lp-ai" id="ai" aria-labelledby="lp-ai-title">
      <div className="lp-container lp-ai-grid">
        <Reveal className="lp-ai-copy">
          <span className="lp-eyebrow">{t("landing.ai.eyebrow")}</span>
          <h2 id="lp-ai-title">{t("landing.ai.title")}</h2>
          <p>{t("landing.ai.body")}</p>
          <ul className="lp-points">
            {[t("landing.ai.point1"), t("landing.ai.point2"), t("landing.ai.point3")].map((point) => (
              <li key={point}><Check size={15} aria-hidden="true" /> {point}</li>
            ))}
          </ul>
          <button className="lp-btn lp-btn-primary" onClick={() => navigate("/app")}>
            <Sparkles size={15} /> {t("landing.ai.cta")}
          </button>
        </Reveal>
        <Reveal delay={140} className="lp-ai-visual">
          <div className="lp-chat">
            <div className="lp-chat-head">
              <span className="lp-chat-mark"><Sparkles size={14} /></span>
              <span className="lp-chat-head-text">
                <strong>AtmosPath assistant</strong>
                <small className="lp-mono"><i className="lp-live-dot" />NL2Opt</small>
              </span>
            </div>
            <div className="lp-chat-body">
              <Reveal delay={240} className="lp-chat-row lp-chat-row-user">
                <span className="lp-bubble lp-bubble-user">{t("landing.ai.chat.user")}</span>
              </Reveal>
              <Reveal delay={460} className="lp-chat-row lp-chat-row-ai">
                <span className="lp-chat-avatar" aria-hidden="true"><Sparkles size={12} /></span>
                <span className="lp-bubble lp-bubble-ai">
                  <span className="lp-chips" aria-hidden="true">
                    <i>{t("landing.ai.chat.chipVehicle")}</i>
                    <i>{t("landing.ai.chat.chipCargo")}</i>
                    <i>{t("landing.ai.chat.chipAvoid")}</i>
                  </span>
                  {t("landing.ai.chat.answer")}
                  <span className="lp-chat-route">
                    <strong>{t("landing.ai.chat.routeLabel")}</strong>
                    <small className="lp-mono">{t("landing.ai.chat.routeStats")}</small>
                  </span>
                  <span className="lp-chat-src">
                    <em>{t("landing.ai.chat.sources")}</em>
                    <span className="lp-mono">
                      {t("landing.ai.chat.source1")} · {t("landing.ai.chat.source2")} · {t("landing.ai.chat.source3")}
                    </span>
                  </span>
                </span>
              </Reveal>
            </div>
            <div className="lp-chat-foot">{t("landing.ai.chat.disclaimer")}</div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function PricingSection() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const tiers = [
    {
      code: "free",
      name: t("landing.pricing.free.name"),
      price: t("landing.pricing.free.price"),
      period: t("landing.pricing.free.period"),
      tagline: t("landing.pricing.free.tagline"),
      features: [
        t("landing.pricing.free.f1"),
        t("landing.pricing.free.f2"),
        t("landing.pricing.free.f3"),
        t("landing.pricing.free.f4"),
      ],
      cta: t("landing.pricing.free.cta"),
      featured: false,
      onSelect: () => navigate("/app/directions"),
    },
    {
      code: "pro",
      name: t("landing.pricing.pro.name"),
      price: t("landing.pricing.pro.price"),
      period: t("landing.pricing.pro.period"),
      tagline: t("landing.pricing.pro.tagline"),
      badge: t("landing.pricing.pro.badge"),
      features: [
        t("landing.pricing.pro.f1"),
        t("landing.pricing.pro.f2"),
        t("landing.pricing.pro.f3"),
        t("landing.pricing.pro.f4"),
        t("landing.pricing.pro.f5"),
      ],
      cta: t("landing.pricing.pro.cta"),
      featured: true,
      onSelect: () => navigate("/app/usage"),
    },
    {
      code: "team",
      name: t("landing.pricing.team.name"),
      price: t("landing.pricing.team.price"),
      period: t("landing.pricing.team.period"),
      tagline: t("landing.pricing.team.tagline"),
      features: [
        t("landing.pricing.team.f1"),
        t("landing.pricing.team.f2"),
        t("landing.pricing.team.f3"),
        t("landing.pricing.team.f4"),
      ],
      cta: t("landing.pricing.team.cta"),
      featured: false,
      onSelect: () => {
        window.location.href = "mailto:hello@atmospath.app";
      },
    },
  ];
  return (
    <section className="lp-section lp-pricing" id="pricing" aria-labelledby="lp-pricing-title">
      <div className="lp-container">
        <Reveal className="lp-section-head">
          <span className="lp-eyebrow">{t("landing.pricing.eyebrow")}</span>
          <h2 id="lp-pricing-title">{t("landing.pricing.title")}</h2>
          <p>{t("landing.pricing.subtitle")}</p>
        </Reveal>
        <div className="lp-tiers">
          {tiers.map((tier, index) => (
            <Reveal key={tier.code} delay={index * 110} className={`lp-tier${tier.featured ? " featured" : ""}`}>
              {tier.badge && <span className="lp-tier-badge">{tier.badge}</span>}
              <header>
                <strong>{tier.name}</strong>
                <small>{tier.tagline}</small>
              </header>
              <div className="lp-tier-price">
                <span className="lp-mono">{tier.price}</span>
                <em>{tier.period}</em>
              </div>
              <ul>
                {tier.features.map((feature) => (
                  <li key={feature}><Check size={14} aria-hidden="true" /> {feature}</li>
                ))}
              </ul>
              <button
                className={`lp-btn lp-tier-cta${tier.featured ? " lp-btn-primary" : " lp-btn-secondary"}`}
                onClick={tier.onSelect}
              >
                {tier.cta}
              </button>
            </Reveal>
          ))}
        </div>
        <p className="lp-pricing-note">{t("landing.pricing.note")}</p>
      </div>
    </section>
  );
}

function CtaBand() {
  const { t } = useI18n();
  const navigate = useNavigate();
  return (
    <section className="lp-cta-band" aria-labelledby="lp-cta-title">
      <div className="lp-container lp-cta-inner">
        <Reveal>
          <h2 id="lp-cta-title">{t("landing.cta.title")}</h2>
          <p>{t("landing.cta.subtitle")}</p>
        </Reveal>
        <Reveal delay={120} className="lp-cta-actions">
          <button className="lp-btn lp-btn-inverse" onClick={() => navigate("/app/map")}>
            {t("landing.cta.button")} <ArrowRight size={16} />
          </button>
          <span className="lp-cta-note lp-mono">{t("landing.cta.note")}</span>
        </Reveal>
      </div>
    </section>
  );
}

function LandingFooter() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const productLinks = [
    { label: t("landing.footer.map"), to: "/app/map" },
    { label: t("landing.footer.dashboard"), to: "/app/dashboard" },
    { label: t("landing.footer.alerts"), to: "/app/alerts" },
    { label: t("landing.footer.saved"), to: "/app/saved" },
    { label: t("landing.footer.pricing"), to: "/app/pricing" },
  ];
  const platformLinks = [
    { label: t("landing.footer.statusPage"), to: "/app/status" },
    { label: t("landing.footer.usage"), to: "/app/usage" },
  ];
  return (
    <footer className="lp-footer">
      <div className="lp-container">
        <div className="lp-footer-grid">
          <div className="lp-footer-brand">
            <Wordmark />
            <p>{t("landing.footer.tagline")}</p>
            <button className="lp-status-chip" onClick={() => navigate("/app/status")}>
              <i className="lp-live-dot" /> {t("landing.footer.status")}
            </button>
          </div>
          <nav aria-label={t("landing.footer.product")}>
            <strong>{t("landing.footer.product")}</strong>
            {productLinks.map((link) => (
              <button key={link.to} onClick={() => navigate(link.to)}>{link.label}</button>
            ))}
          </nav>
          <nav aria-label={t("landing.footer.platform")}>
            <strong>{t("landing.footer.platform")}</strong>
            {platformLinks.map((link) => (
              <button key={link.to} onClick={() => navigate(link.to)}>{link.label}</button>
            ))}
          </nav>
          <nav aria-label={t("landing.footer.company")}>
            <strong>{t("landing.footer.company")}</strong>
            <a href="#top">{t("landing.footer.about")}</a>
            <a href="mailto:hello@atmospath.app">{t("landing.footer.contact")}</a>
          </nav>
        </div>
        <div className="lp-footer-bottom">
          <span>{t("landing.footer.rights")}</span>
          <span className="lp-footer-data">{t("landing.footer.dataNote")}</span>
          <LanguageToggle />
        </div>
      </div>
    </footer>
  );
}

export default function LandingPage() {
  useEffect(() => {
    window.scrollTo(0, 0);
  }, []);
  return (
    <div className="landing">
      <LandingHeader />
      <main>
        <HeroSection />
        <HowSection />
        <CompareSection />
        <FeaturesSection />
        <AiSection />
        <PricingSection />
        <CtaBand />
      </main>
      <LandingFooter />
    </div>
  );
}
