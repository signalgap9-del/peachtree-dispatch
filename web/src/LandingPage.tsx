import {
  ArrowRight,
  ArrowUpRight,
  Car,
  Caravan,
  Check,
  CheckCircle2,
  CloudRain,
  Gauge,
  MapPin,
  Menu,
  Mic,
  Radar,
  Route,
  Send,
  ShieldAlert,
  ShieldCheck,
  Thermometer,
  TriangleAlert,
  Truck,
  Webhook,
  Wind,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";

import "./LandingPage.css";

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/* ---------- motion primitives ---------- */

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

function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const { ref, inView } = useInView<HTMLDivElement>();
  return (
    <div
      ref={ref}
      className={`fs-reveal${inView ? " is-in" : ""}${className ? ` ${className}` : ""}`}
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

/* ---------- brand mark ---------- */

function FreightMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="8" fill="#0B57D0" />
      <path
        d="M6.5 23.5 C 12 22, 13 15, 18 13.5 S 24.5 9.5, 26 8"
        fill="none"
        stroke="#FFFFFF"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <circle cx="6.5" cy="23.5" r="2.6" fill="#81C995" />
      <circle cx="17.6" cy="13.8" r="2.4" fill="#F9AB00" />
      <rect x="23.6" y="5.6" width="4.8" height="4.8" rx="1.2" fill="#FFFFFF" transform="rotate(45 26 8)" />
    </svg>
  );
}

function Brand({ onClick }: { onClick?: () => void }) {
  return (
    <a className="fs-brand" href="#top" onClick={onClick}>
      <FreightMark />
      <span className="fs-brand-name">
        Freight<b>Scaler</b>
      </span>
    </a>
  );
}

/* ---------- header ---------- */

const NAV_LINKS = [
  { href: "#how", label: "How it works" },
  { href: "#compare", label: "Route call" },
  { href: "#assistant", label: "Assistant" },
  { href: "#features", label: "Features" },
  { href: "#pricing", label: "Pricing" },
];

function LandingHeader() {
  const navigate = useNavigate();
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  return (
    <header className={`fs-header${scrolled ? " scrolled" : ""}`}>
      <div className="fs-shell fs-header-inner">
        <Brand onClick={() => setOpen(false)} />
        <nav className="fs-nav" aria-label="FreightScaler landing">
          {NAV_LINKS.map((link) => (
            <a key={link.href} href={link.href}>
              {link.label}
            </a>
          ))}
        </nav>
        <div className="fs-header-actions">
          <button className="fs-btn fs-btn-ghost fs-btn-sm" onClick={() => navigate("/app")}>
            Sign in
          </button>
          <button className="fs-btn fs-btn-primary fs-btn-sm" onClick={() => navigate("/app/map")}>
            Open the app <ArrowUpRight size={15} />
          </button>
          <button
            className="fs-menu-btn"
            aria-label={open ? "Close menu" : "Open menu"}
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
          >
            {open ? <X size={19} /> : <Menu size={19} />}
          </button>
        </div>
      </div>
      <nav className={`fs-mobile-nav${open ? " open" : ""}`} aria-label="FreightScaler landing mobile">
        {NAV_LINKS.map((link) => (
          <a key={link.href} href={link.href} onClick={() => setOpen(false)}>
            {link.label}
          </a>
        ))}
        <button
          className="fs-btn fs-btn-primary"
          onClick={() => {
            setOpen(false);
            navigate("/app/map");
          }}
        >
          Open the app <ArrowUpRight size={15} />
        </button>
      </nav>
    </header>
  );
}

/* ---------- hero ---------- */

function HazardChip({
  x,
  y,
  label,
  color,
  below = false,
}: {
  x: number;
  y: number;
  label: string;
  color: string;
  below?: boolean;
}) {
  const dir = below ? 1 : -1;
  return (
    <g className="fs-haz" transform={`translate(${x} ${y})`}>
      <line x1="0" y1={6 * dir} x2="0" y2={13 * dir} stroke="#DADCE0" strokeWidth="1" />
      <rect x="-29" y={below ? 13 : -32} width="58" height="19" rx="9.5" fill="#FFFFFF" stroke="#DADCE0" />
      <circle cx="-19" cy={below ? 22.5 : -22.5} r="3.5" fill={color} />
      <text
        x="-11"
        y={below ? 25.5 : -19.5}
        fontSize="9"
        fill="#3C4043"
        fontFamily="'DM Mono', monospace"
        letterSpacing="0.4"
      >
        {label}
      </text>
    </g>
  );
}

function RouteBoard() {
  const { ref, inView } = useInView<HTMLDivElement>(0.25);
  return (
    <div className="fs-board-wrap" ref={ref}>
      <div className="fs-float fs-float-risk">
        <span className="fs-float-icon" style={{ background: "var(--fs-green-soft)", color: "var(--fs-green)" }}>
          <ShieldCheck size={18} />
        </span>
        <span>
          <small>Route swap</small>
          <strong>
            <b>58</b>
            <i>&rarr;</i>
            <u>15</u>
          </strong>
        </span>
      </div>
      <div className={`fs-board${inView ? " is-drawn" : ""}`}>
        <div className="fs-board-bar">
          <span>
            <strong>SEA &rarr; MIA</strong> &middot; 2 ROUTES SCORED
          </span>
          <span className="fs-board-live">
            <i className="fs-live-dot fs-live-dot-green" /> LIVE &middot; NWS FEED
          </span>
        </div>
        <svg className="fs-board-map" viewBox="0 0 720 520" role="img" aria-label="Map comparing a risky fastest route against a lower-risk route from Seattle to Miami">
          <defs>
            <pattern id="fs-dots" width="24" height="24" patternUnits="userSpaceOnUse">
              <circle cx="1.5" cy="1.5" r="1.2" fill="#C9D6E4" />
            </pattern>
          </defs>
          <rect width="720" height="520" fill="url(#fs-dots)" opacity="0.55" />

          {/* faint context highways */}
          <g fill="none" stroke="#DDE6F0" strokeWidth="1.5">
            <path d="M30 150 C 200 130, 400 120, 700 90" />
            <path d="M60 250 C 250 240, 450 250, 700 210" />
            <path d="M30 380 C 250 370, 480 380, 700 340" strokeDasharray="3 5" />
            <path d="M250 30 C 260 180, 300 330, 360 500" />
            <path d="M520 30 C 540 160, 560 300, 600 500" />
          </g>

          {/* context cities */}
          <g>
            <circle cx="480" cy="140" r="2.5" fill="#A8B9CC" />
            <text className="fs-city" x="480" y="128" textAnchor="middle">CHI</text>
            <circle cx="285" cy="215" r="2.5" fill="#A8B9CC" />
            <text className="fs-city" x="285" y="203" textAnchor="middle">DEN</text>
            <circle cx="185" cy="330" r="2.5" fill="#A8B9CC" />
            <text className="fs-city" x="185" y="318" textAnchor="middle">PHX</text>
            <circle cx="375" cy="350" r="2.5" fill="#A8B9CC" />
            <text className="fs-city" x="392" y="364">DAL</text>
            <circle cx="545" cy="320" r="2.5" fill="#A8B9CC" />
            <text className="fs-city" x="545" y="308" textAnchor="middle">ATL</text>
          </g>

          {/* storm system over the Gulf */}
          <g>
            <circle className="fs-storm-ring" cx="470" cy="405" r="46" fill="none" stroke="#D93025" strokeWidth="1.5" opacity="0.5" />
            <circle cx="470" cy="405" r="30" fill="#D93025" opacity="0.1" />
            <circle cx="470" cy="405" r="4" fill="#D93025" />
            <text className="fs-map-note" x="470" y="472" textAnchor="middle" fill="#C5221F">STORM TRACK</text>
          </g>

          {/* route A - fastest, risky */}
          <path
            className="fs-route"
            pathLength={1}
            d="M56 74 C 160 150, 240 210, 320 290 C 380 350, 420 380, 470 405 C 530 435, 590 448, 652 452"
            stroke="#D93025"
            strokeWidth="3.5"
          />
          {/* route B - lower risk */}
          <path
            className="fs-route fs-route-b"
            pathLength={1}
            d="M56 74 C 170 52, 320 56, 430 92 C 530 125, 596 180, 620 250 C 644 320, 650 390, 652 452"
            stroke="#188038"
            strokeWidth="3.5"
          />

          {/* route labels */}
          <text className="fs-map-note" x="262" y="40" textAnchor="middle" fill="#188038">ROUTE B &middot; 4,039 MI</text>
          <text className="fs-map-note" x="128" y="178" textAnchor="middle" fill="#C5221F">ROUTE A &middot; 3,658 MI</text>

          {/* hazard chips along route A */}
          <HazardChip x={232} y={208} label="WIND 22" color="#F9AB00" />
          <HazardChip x={330} y={300} label="HEAT 28" color="#E8710A" />
          <HazardChip x={452} y={396} label="RAIN 23" color="#669DF6" below />
          <HazardChip x={575} y={442} label="RAIN 28" color="#669DF6" />

          {/* endpoints */}
          <rect x="51.5" y="69.5" width="9" height="9" rx="2" fill="#0B57D0" />
          <text className="fs-city" x="56" y="58" textAnchor="middle" fill="#0B57D0">SEA</text>
          <rect x="-4.5" y="-4.5" width="9" height="9" rx="2" fill="#0B57D0" transform="translate(652 452) rotate(45)" />
          <text className="fs-city" x="652" y="476" textAnchor="middle" fill="#0B57D0">MIA</text>

          {/* compass + scale */}
          <g stroke="#9AA9BA" strokeWidth="1.2" fill="none">
            <path d="M686 44 L686 30 M682 34 L686 30 L690 34" />
          </g>
          <text className="fs-map-note" x="686" y="24" textAnchor="middle">N</text>
          <g stroke="#9AA9BA" strokeWidth="1.2">
            <line x1="24" y1="494" x2="104" y2="494" />
            <line x1="24" y1="490" x2="24" y2="498" />
            <line x1="104" y1="490" x2="104" y2="498" />
          </g>
          <text className="fs-map-note" x="112" y="497">500 MI</text>
        </svg>
        <div className="fs-board-legend">
          <span>
            <i className="fs-legend-line" style={{ background: "#188038" }} /> ROUTE B &middot; LOWER RISK &middot; 77.9 HRS
          </span>
          <span>
            <i className="fs-legend-line" style={{ background: "#D93025" }} /> ROUTE A &middot; FASTEST &middot; 69.4 HRS
          </span>
        </div>
      </div>
      <div className="fs-float fs-float-delay">
        <span className="fs-float-icon" style={{ background: "var(--fs-red-soft)", color: "var(--fs-red)" }}>
          <TriangleAlert size={17} />
        </span>
        <span>
          <small>Weather delay &middot; Route A</small>
          <strong>628 MIN</strong>
        </span>
      </div>
    </div>
  );
}

function HeroSection() {
  const navigate = useNavigate();
  return (
    <section className="fs-hero" id="top" aria-labelledby="fs-hero-title">
      <div className="fs-shell fs-hero-grid">
        <div>
          <Reveal>
            <span className="fs-live-chip">
              <i className="fs-live-dot" /> 42 active weather alerts on US highways
            </span>
          </Reveal>
          <Reveal delay={90}>
            <h1 className="fs-h1" id="fs-hero-title">
              Know the
              <br />
              <em>safer</em> route
              <br />
              before you leave.
            </h1>
          </Reveal>
          <Reveal delay={180}>
            <p className="fs-hero-sub">
              FreightScaler is an AI risk engine for every driver &mdash; car, van, or truck. It scores every
              possible route against live NWS / NOAA alerts, forecast models, and historical weather delay, so
              you know which way is safer before you turn the key.
            </p>
          </Reveal>
          <Reveal delay={260}>
            <div className="fs-hero-cta">
              <button className="fs-btn fs-btn-primary" onClick={() => navigate("/app/map")}>
                Plan a route <ArrowRight size={16} />
              </button>
              <a className="fs-btn fs-btn-outline" href="#compare">
                See a real route call
              </a>
            </div>
          </Reveal>
          <Reveal delay={340}>
            <div className="fs-hero-stats">
              <div>
                <strong>3,658 mi</strong>
                <span>Fastest route</span>
              </div>
              <div>
                <strong>4,039 mi</strong>
                <span>Lower-risk route</span>
              </div>
              <div>
                <strong>10.5 hrs</strong>
                <span>Storm delay dodged</span>
              </div>
            </div>
          </Reveal>
        </div>
        <Reveal delay={200}>
          <RouteBoard />
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- metric strip ---------- */

function MetricStrip() {
  const { ref, inView } = useInView<HTMLDivElement>(0.3);
  const alerts = useCountBetween(0, 42, inView, 1200);
  const scores = useCountBetween(0, 14, inView, 1600);
  const bulletins = useCountBetween(0, 38500, inView, 1800);
  const delay = useCountBetween(0, 628, inView, 1800);
  return (
    <section className="fs-strip" aria-label="FreightScaler by the numbers">
      <div className="fs-shell fs-strip-grid" ref={ref}>
        <div className="fs-strip-cell">
          <strong>
            {alerts}
            <span className="fs-strip-live">LIVE</span>
          </strong>
          <span>Active weather alerts on major routes right now</span>
        </div>
        <div className="fs-strip-cell">
          <strong>{(scores / 10).toFixed(1)}M</strong>
          <span>Risk scores computed daily</span>
        </div>
        <div className="fs-strip-cell">
          <strong>{bulletins.toLocaleString("en-US")}</strong>
          <span>NWS / NOAA bulletins processed monthly</span>
        </div>
        <div className="fs-strip-cell">
          <strong>
            {delay}
            <span className="fs-strip-unit">MIN</span>
          </strong>
          <span>Weather delay flagged on the SEA &rarr; MIA fast route</span>
        </div>
      </div>
    </section>
  );
}

/* ---------- risk threshold intelligence ---------- */

function ThresholdGauge() {
  const { ref, inView } = useInView<HTMLDivElement>(0.4);
  return (
    <div className="fs-gauge" ref={ref}>
      <div className={`fs-gauge-track${inView ? " is-in" : ""}`}>
        <i className="fs-gauge-fill" />
        <span className="fs-gauge-mark" style={{ left: "40%" }}>
          <em>DETOUR THRESHOLD &middot; 40</em>
        </span>
      </div>
      <div className="fs-gauge-labels">
        <span>0 &middot; CALM</span>
        <span>100 &middot; SEVERE</span>
      </div>
    </div>
  );
}

function ThresholdSection() {
  return (
    <section className="fs-thresh" id="threshold" aria-labelledby="fs-thresh-title">
      <div className="fs-shell">
        <div className="fs-thresh-head">
          <Reveal>
            <span className="fs-eyebrow">Risk threshold engine</span>
            <h2 className="fs-h2" id="fs-thresh-title">
              We don&rsquo;t cry wolf.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              FreightScaler recommends your fastest route when it&rsquo;s safe &mdash; and only suggests a detour
              when risk actually warrants it. No jump scares, no fifty reroutes a day. One threshold, applied to
              every mile.
            </p>
          </Reveal>
        </div>
        <Reveal delay={160}>
          <ThresholdGauge />
        </Reveal>
        <div className="fs-thresh-cards">
          <Reveal delay={0}>
            <article className="fs-thresh-card fs-thresh-ok">
              <div className="fs-thresh-meta">
                <span>
                  <i className="fs-live-dot fs-live-dot-green" /> TUESDAY 06:00 &middot; I-90 W
                </span>
                <em className="fs-thresh-chip fs-thresh-chip-ok">RISK 12</em>
              </div>
              <svg className="fs-thresh-map" viewBox="0 0 300 96" aria-hidden="true">
                <path
                  className="fs-thresh-line"
                  pathLength={1}
                  d="M14 66 C 80 58, 150 52, 286 40"
                  fill="none"
                  stroke="#188038"
                  strokeWidth="3.5"
                  strokeLinecap="round"
                />
                <circle cx="14" cy="66" r="4" fill="#0B57D0" />
                <rect x="-3.5" y="-3.5" width="7" height="7" rx="1.8" fill="#0B57D0" transform="translate(286 40) rotate(45)" />
              </svg>
              <h3>Take the fast route</h3>
              <p>Nothing on the radar. We point you at the quickest way and stay out of your lane.</p>
            </article>
          </Reveal>
          <Reveal delay={140}>
            <article className="fs-thresh-card fs-thresh-warn">
              <div className="fs-thresh-meta">
                <span>
                  <i className="fs-live-dot" /> FRIDAY 06:00 &middot; I-10 S
                </span>
                <em className="fs-thresh-chip fs-thresh-chip-warn">RISK 58</em>
              </div>
              <svg className="fs-thresh-map" viewBox="0 0 300 96" aria-hidden="true">
                <path
                  className="fs-thresh-line"
                  pathLength={1}
                  d="M14 66 C 90 62, 180 56, 286 40"
                  fill="none"
                  stroke="#D93025"
                  strokeWidth="2.5"
                  strokeDasharray="5 6"
                  strokeLinecap="round"
                  opacity="0.55"
                />
                <path
                  className="fs-thresh-line fs-thresh-line-alt"
                  pathLength={1}
                  d="M14 66 C 70 30, 170 18, 230 26 C 262 30, 278 34, 286 40"
                  fill="none"
                  stroke="#188038"
                  strokeWidth="3.5"
                  strokeLinecap="round"
                />
                <circle cx="168" cy="58" r="9" fill="#D93025" opacity="0.14" />
                <circle cx="168" cy="58" r="3" fill="#D93025" />
                <circle cx="14" cy="66" r="4" fill="#0B57D0" />
                <rect x="-3.5" y="-3.5" width="7" height="7" rx="1.8" fill="#0B57D0" transform="translate(286 40) rotate(45)" />
              </svg>
              <h3>Here&rsquo;s the safer way</h3>
              <p>
                A storm track crosses I-10. You get a 42-mile detour with the numbers attached &mdash; and only
                then.
              </p>
            </article>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

/* ---------- how it works ---------- */

function HowSection() {
  return (
    <section className="fs-how" id="how" aria-labelledby="fs-how-title">
      <div className="fs-shell">
        <div className="fs-how-head">
          <Reveal>
            <span className="fs-eyebrow">How it works</span>
            <h2 className="fs-h2" id="fs-how-title">
              Three moves.
              <br />
              One safer trip.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              No weather tab, no radar app, no gut feel. FreightScaler folds risk straight into the routing &mdash;
              for a school run or a 4,000-mile haul.
            </p>
          </Reveal>
        </div>
        <div className="fs-steps">
          <svg className="fs-steps-line" aria-hidden="true" width="100%" height="3">
            <line x1="0" y1="1.5" x2="100%" y2="1.5" />
          </svg>
          <Reveal className="fs-step" delay={0}>
            <span className="fs-step-badge">01</span>
            <div className="fs-step-card">
              <h3>Plan the trip</h3>
              <p>Pick your vehicle, drop origin and destination, set your window &mdash; or just type it the way you&rsquo;d say it out loud.</p>
              <div className="fs-step-mock">
                <div className="fs-step-input">
                  <MapPin size={13} /> SEA <ArrowRight size={12} /> MIA
                </div>
                <div className="fs-step-chips">
                  <i>VAN</i>
                  <i>DEPART 08:00</i>
                  <i>PREFER LOW RISK</i>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-step" delay={140}>
            <span className="fs-step-badge">02</span>
            <div className="fs-step-card">
              <h3>Compare the risk</h3>
              <p>Every route gets a 0&ndash;100 score built from live alerts, forecast models, and historical weather delay.</p>
              <div className="fs-step-mock">
                <div className="fs-step-lane">
                  <b>RT A</b>
                  <span className="fs-step-bar">
                    <i style={{ "--w": "58%", background: "var(--fs-red)" } as CSSProperties} />
                  </span>
                  <em style={{ color: "var(--fs-red)" }}>58</em>
                </div>
                <div className="fs-step-lane">
                  <b>RT B</b>
                  <span className="fs-step-bar">
                    <i style={{ "--w": "15%", background: "var(--fs-green)" } as CSSProperties} />
                  </span>
                  <em style={{ color: "var(--fs-green)" }}>15</em>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-step" delay={280}>
            <span className="fs-step-badge">03</span>
            <div className="fs-step-card">
              <h3>Watch the road</h3>
              <p>Watches stay open until you park. If weather moves onto your route, you hear it first.</p>
              <div className="fs-step-mock">
                <div className="fs-step-alert">
                  <i className="fs-live-dot" />
                  <span>High wind advisory &middot; I-10, AZ</span>
                  <em>14:32Z</em>
                </div>
              </div>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

/* ---------- route call (dark) ---------- */

function Sparkline({ points, color }: { points: string; color: string }) {
  return (
    <svg viewBox="0 0 240 40" preserveAspectRatio="none" aria-hidden="true">
      <line x1="0" y1="14" x2="240" y2="14" stroke="rgba(255,255,255,0.14)" strokeDasharray="3 4" />
      <polyline points={points} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

function CompareSection() {
  return (
    <section className="fs-compare" id="compare" aria-labelledby="fs-compare-title">
      <div className="fs-shell">
        <div className="fs-compare-head">
          <Reveal>
            <span className="fs-eyebrow">Route call &middot; SEA &rarr; MIA</span>
            <h2 className="fs-h2" id="fs-compare-title">
              The fastest route isn&rsquo;t always the smart route.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              Same trip, same Friday deadline. Two ways to drive it &mdash; here&rsquo;s the call FreightScaler put
              in front of the driver.
            </p>
          </Reveal>
        </div>
        <div className="fs-lanes">
          <Reveal delay={0}>
            <article className="fs-lane-card fs-lane-a">
              <div className="fs-lane-tags">
                <span className="fs-lane-tag">ROUTE A &middot; FASTEST</span>
                <span className="fs-risk-badge fs-risk-high">
                  <TriangleAlert size={12} /> RISK 58 &middot; HIGH
                </span>
              </div>
              <h3 className="fs-lane-name">I-10 S Corridor</h3>
              <div className="fs-lane-spark">
                <Sparkline points="0,30 30,26 55,33 80,12 105,24 130,7 160,20 190,5 215,16 240,9" color="#D93025" />
                <small>RISK PROFILE ALONG ROUTE</small>
              </div>
              <div className="fs-lane-stats">
                <div>
                  <strong>3,658<small>mi</small></strong>
                  <span>Distance</span>
                </div>
                <div>
                  <strong>69.4<small>hrs</small></strong>
                  <span>Drive time</span>
                </div>
                <div>
                  <strong className="fs-hot">628<small>min</small></strong>
                  <span>Weather delay</span>
                </div>
              </div>
              <div className="fs-hazbar">
                <div className="fs-hazbar-track">
                  <i className="fs-haz-wind" style={{ flex: "22 1 0%" }} />
                  <i className="fs-haz-heat" style={{ flex: "28 1 0%" }} />
                  <i className="fs-haz-rain" style={{ flex: "23 1 0%" }} />
                  <i className="fs-haz-rain" style={{ flex: "28 1 0%" }} />
                </div>
                <div className="fs-hazbar-labels">
                  <span className="fs-haz-wind-t"><Wind /> WIND 22</span>
                  <span className="fs-haz-heat-t"><Thermometer /> HEAT 28</span>
                  <span className="fs-haz-rain-t"><CloudRain /> RAIN 23</span>
                  <span className="fs-haz-rain-t"><CloudRain /> RAIN 28</span>
                </div>
              </div>
              <p className="fs-lane-note">
                Runs straight through an active Gulf storm track &mdash; three hazard segments, one of them severe.
                10.5 hours of weather delay baked into the &ldquo;fastest&rdquo; ETA.
              </p>
            </article>
          </Reveal>
          <Reveal delay={140}>
            <article className="fs-lane-card fs-lane-b">
              <div className="fs-lane-tags">
                <span className="fs-lane-tag">ROUTE B &middot; LOWER RISK</span>
                <span className="fs-risk-badge fs-risk-low">
                  <ShieldCheck size={12} /> RISK 15 &middot; RECOMMENDED
                </span>
              </div>
              <h3 className="fs-lane-name">I-40 E &rarr; I-81 S</h3>
              <div className="fs-lane-spark">
                <Sparkline points="0,30 40,29 80,31 120,28 160,30 200,27 240,29" color="#34A853" />
                <small>RISK PROFILE ALONG ROUTE</small>
              </div>
              <div className="fs-lane-stats">
                <div>
                  <strong>4,039<small>mi</small></strong>
                  <span>Distance</span>
                </div>
                <div>
                  <strong>77.9<small>hrs</small></strong>
                  <span>Drive time</span>
                </div>
                <div>
                  <strong className="fs-cool">0<small>min</small></strong>
                  <span>Weather delay</span>
                </div>
              </div>
              <div className="fs-hazbar">
                <div className="fs-hazbar-track">
                  <i className="fs-haz-clear" style={{ flex: "1 1 0%" }} />
                </div>
                <div className="fs-hazbar-labels">
                  <span className="fs-haz-clear-t"><CheckCircle2 /> CLEAR &middot; NO ACTIVE HAZARD SEGMENTS</span>
                </div>
              </div>
              <p className="fs-lane-note">
                381 extra miles that dodge the storm entirely, hold the arrival window, and keep the trip &mdash; and
                everyone in the vehicle &mdash; out of the weather&rsquo;s hands.
              </p>
            </article>
          </Reveal>
        </div>
        <Reveal delay={200}>
          <div className="fs-verdict">
            <span className="fs-verdict-icon">
              <CheckCircle2 size={20} />
            </span>
            <p>
              <b>THE CALL: ROUTE B &mdash; I-40 E &rarr; I-81 S</b>
              <span>8.5 extra hours of driving buys back 10.5 hours of storm delay and clears the Gulf track entirely.</span>
            </p>
            <em>RISK ENGINE v2.4 &middot; SCORED 06:12 CT</em>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- AI assistant ---------- */

function AssistantSection() {
  const navigate = useNavigate();
  return (
    <section className="fs-dispatch" id="assistant" aria-labelledby="fs-assistant-title">
      <div className="fs-shell fs-dispatch-grid">
        <div>
          <Reveal>
            <span className="fs-eyebrow">AI assistant</span>
            <h2 className="fs-h2" id="fs-assistant-title">
              Ask in plain English.
            </h2>
            <p className="fs-lede">
              No forms, no drop-downs. Type the trip the way you&rsquo;d say it &mdash; a weekend drive or a hazmat
              haul. FreightScaler reads the constraints, scores the routes, and explains the call.
            </p>
          </Reveal>
          <Reveal delay={140}>
            <ul className="fs-checklist">
              <li>
                <i><Check size={13} /></i> Reads plain-English trips, any vehicle
              </li>
              <li>
                <i><Check size={13} /></i> Knows truck &amp; FHWA hazmat rules (Pro)
              </li>
              <li>
                <i><Check size={13} /></i> Returns scored routes in about a second
              </li>
            </ul>
          </Reveal>
          <Reveal delay={220}>
            <button className="fs-dispatch-link" onClick={() => navigate("/app")}>
              Open the assistant <ArrowRight size={16} />
            </button>
          </Reveal>
        </div>
        <Reveal delay={160}>
          <div className="fs-chat" role="img" aria-label="Example assistant conversation: everyday and hazmat trip requests turned into a scored route">
            <div className="fs-chat-bar">
              <span>
                <i className="fs-live-dot fs-live-dot-green" /> ASSISTANT &middot; SEA &rarr; MIA
              </span>
              <em>06:04 CT</em>
            </div>
            <div className="fs-chat-body">
              <Reveal delay={80}>
                <p className="fs-msg-user">
                  Seattle to Miami on Friday &mdash; is the fast route safe?
                </p>
              </Reveal>
              <Reveal delay={220}>
                <div className="fs-msg-ai">
                  <p>
                    Not this week. A Gulf storm track crosses I-10 around Friday &mdash; risk on the fast route is
                    58, above my detour threshold.
                  </p>
                </div>
              </Reveal>
              <Reveal delay={360}>
                <p className="fs-msg-user">
                  Got it. Same trip, but I&rsquo;m hauling hazmat &mdash; keep it legal.
                </p>
              </Reveal>
              <Reveal delay={500}>
                <div className="fs-msg-ai">
                  <p>Copy. Locking constraints:</p>
                  <div className="fs-constraints">
                    <i>MODE=TRUCK</i>
                    <i>HAZMAT=TRUE</i>
                    <i>AVOID=STORM TRACK</i>
                    <i>TARGET=MIN RISK</i>
                  </div>
                </div>
              </Reveal>
              <Reveal delay={640}>
                <div className="fs-chat-route">
                  <strong>Route B &middot; I-40 E &rarr; I-81 S</strong>
                  <b>4,039 MI &middot; 77.9 HRS &middot; RISK 15</b>
                  <p>
                    Clears the storm track and every hazmat-restricted corridor on the way. Arrival 14 hours ahead
                    of the cell.
                  </p>
                  <em>SOURCES: NWS &middot; NOAA &middot; FHWA HAZMAT</em>
                </div>
              </Reveal>
              <Reveal delay={760}>
                <div className="fs-chat-status">
                  <i /> SOLVED IN 1.2 S &middot; 2 ROUTES SCORED
                </div>
              </Reveal>
            </div>
            <div className="fs-chat-input">
              <span>Ask about any trip&hellip;</span>
              <Mic size={16} />
              <span className="fs-chat-send">
                <Send size={15} />
              </span>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- bento features ---------- */

function HazmatVisual() {
  return (
    <svg className="fs-hazmap" viewBox="0 0 320 150" aria-hidden="true">
      <defs>
        <pattern id="fs-hatch" width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1="0" y1="0" x2="0" y2="7" stroke="#F28B82" strokeWidth="2" />
        </pattern>
      </defs>
      <path d="M12 75 H 308" stroke="#E3E9F0" strokeWidth="10" strokeLinecap="round" fill="none" />
      <rect x="132" y="47" width="78" height="56" rx="6" fill="url(#fs-hatch)" stroke="#D93025" strokeOpacity="0.55" strokeDasharray="4 4" />
      <path
        d="M12 75 H 108 C 124 75 126 42 146 33 C 166 24 186 24 202 35 C 216 45 218 75 234 75 H 308"
        fill="none"
        stroke="#188038"
        strokeWidth="3"
        strokeLinecap="round"
      />
      <circle cx="12" cy="75" r="4.5" fill="#0B57D0" />
      <rect x="-4" y="-4" width="8" height="8" rx="2" fill="#0B57D0" transform="translate(308 75) rotate(45)" />
      <text x="171" y="16" textAnchor="middle" fontSize="8.5" fill="#188038" fontFamily="'DM Mono', monospace" letterSpacing="0.8">CLEARED REROUTE</text>
      <text x="171" y="138" textAnchor="middle" fontSize="8.5" fill="#C5221F" fontFamily="'DM Mono', monospace" letterSpacing="0.8">HAZMAT RESTRICTED ZONE</text>
    </svg>
  );
}

/* ---------- vehicle modes + features ---------- */

const MODES = [
  {
    id: "car",
    label: "Car",
    icon: Car,
    tier: "FREE",
    note: "Daily drives and road trips - live alerts, risk scores, and saved routes.",
  },
  {
    id: "van",
    label: "Van",
    icon: Caravan,
    tier: "FREE + PRO",
    note: "Campervans, cargo vans, and towing - add commercial routing and corridor watches.",
  },
  {
    id: "truck",
    label: "Truck",
    icon: Truck,
    tier: "PRO",
    note: "Commercial miles - hazmat rules, fleet dispatch, corridor monitoring, and API access.",
  },
] as const;

type ModeId = (typeof MODES)[number]["id"];

function ModesBand() {
  const [mode, setMode] = useState<ModeId>("car");
  const active = MODES.find((m) => m.id === mode) ?? MODES[0];
  const ActiveIcon = active.icon;
  return (
    <Reveal delay={100}>
      <div className="fs-modes" role="group" aria-label="Vehicle modes">
        {MODES.map(({ id, label, icon: Icon, tier }) => (
          <button
            key={id}
            className={`fs-mode${mode === id ? " active" : ""}`}
            aria-pressed={mode === id}
            onClick={() => setMode(id)}
          >
            <Icon size={17} />
            <span>{label}</span>
            <i>{tier}</i>
          </button>
        ))}
      </div>
      <p className="fs-mode-note" key={mode}>
        <ActiveIcon size={15} />
        <b>{active.tier}</b>
        <span>{active.note}</span>
      </p>
    </Reveal>
  );
}

function FeaturesSection() {
  return (
    <section className="fs-features" id="features" aria-labelledby="fs-features-title">
      <div className="fs-shell">
        <Reveal>
          <span className="fs-eyebrow">Car &middot; Van &middot; Truck</span>
          <h2 className="fs-h2" id="fs-features-title">
            One risk engine,
            <br />
            every driver.
          </h2>
          <p className="fs-lede">
            Free for everyday driving. Pro unlocks the commercial layer &mdash; hazmat, fleet dispatch, and
            corridor monitoring.
          </p>
        </Reveal>
        <ModesBand />
        <div className="fs-bento">
          <Reveal className="fs-cell-alerts" delay={0}>
            <div className="fs-cell">
              <span className="fs-cell-icon">
                <Radar size={20} />
              </span>
              <h3>Live weather alerts</h3>
              <p>
                NWS and NOAA watches, warnings, and advisories &mdash; folded straight into the routes you drive,
                the moment they&rsquo;re issued.
              </p>
              <div className="fs-cell-visual">
                <div className="fs-feed">
                  <div className="fs-feed-row">
                    <i className="fs-live-dot fs-live-dot-red" />
                    <span>Tornado watch &mdash; Cooke County, TX</span>
                    <em>14:32Z</em>
                    <i>NWS</i>
                  </div>
                  <div className="fs-feed-row">
                    <i className="fs-live-dot" />
                    <span>High wind advisory &mdash; I-10, AZ</span>
                    <em>13:58Z</em>
                    <i>NOAA</i>
                  </div>
                  <div className="fs-feed-row">
                    <i className="fs-live-dot" />
                    <span>Winter storm watch &mdash; I-80, WY</span>
                    <em>12:41Z</em>
                    <i>NWS</i>
                  </div>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-scores" delay={120}>
            <div className="fs-cell">
              <span className="fs-cell-icon fs-cell-icon-green">
                <Gauge size={20} />
              </span>
              <h3>Route risk scores</h3>
              <p>Every option gets a 0&ndash;100 score built from live alerts, forecast models, and historical delay. Not a vibe &mdash; a number.</p>
              <div className="fs-cell-visual">
                <div className="fs-step-lane">
                  <b>RT A</b>
                  <span className="fs-step-bar">
                    <i style={{ "--w": "58%", background: "var(--fs-red)" } as CSSProperties} />
                  </span>
                  <em style={{ color: "var(--fs-red)" }}>58</em>
                </div>
                <div className="fs-step-lane">
                  <b>RT B</b>
                  <span className="fs-step-bar">
                    <i style={{ "--w": "15%", background: "var(--fs-green)" } as CSSProperties} />
                  </span>
                  <em style={{ color: "var(--fs-green)" }}>15</em>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-saved" delay={180}>
            <div className="fs-cell">
              <span className="fs-cell-icon fs-cell-icon-amber">
                <Route size={20} />
              </span>
              <h3>Saved routes</h3>
              <p>Save the trips you run every week. We watch them and ping you when weather moves in.</p>
              <div className="fs-cell-visual">
                <div className="fs-step-input">
                  <Route size={13} /> HOME &rarr; OFFICE &middot; WATCHED DAILY
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-haz" delay={240}>
            <div className="fs-cell">
              <span className="fs-pro-badge">PRO</span>
              <span className="fs-cell-icon fs-cell-icon-red">
                <ShieldAlert size={20} />
              </span>
              <h3>Hazmat-aware routing</h3>
              <p>Routes around restricted corridors, tunnels, and dense zones &mdash; with the compliance trail attached.</p>
              <div className="fs-cell-visual">
                <HazmatVisual />
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-flt" delay={160}>
            <div className="fs-cell">
              <span className="fs-pro-badge">PRO</span>
              <span className="fs-cell-icon">
                <Truck size={20} />
              </span>
              <h3>Fleet dispatch board</h3>
              <p>Every power unit, every route, one board &mdash; risk per truck, not per spreadsheet.</p>
              <div className="fs-cell-visual">
                <div className="fs-feed">
                  <div className="fs-fleet-row">
                    <b>TRK-114</b>
                    <span>I-40 E &middot; 412 mi out</span>
                    <em className="fs-risk-ok">RISK 12</em>
                  </div>
                  <div className="fs-fleet-row">
                    <b>TRK-207</b>
                    <span>I-10 S &middot; Gulf track</span>
                    <em className="fs-risk-bad">RISK 58</em>
                  </div>
                  <div className="fs-fleet-row">
                    <b>TRK-331</b>
                    <span>I-81 N &middot; 96 mi out</span>
                    <em className="fs-risk-ok">RISK 21</em>
                  </div>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-api" delay={220}>
            <div className="fs-cell">
              <span className="fs-pro-badge">PRO</span>
              <span className="fs-cell-icon fs-cell-icon-amber">
                <Webhook size={20} />
              </span>
              <h3>API + webhooks</h3>
              <p>Score routes from your own TMS or app with a REST API and outbound webhooks.</p>
              <div className="fs-cell-visual">
                <pre className="fs-code">
                  <i>$</i> POST /v1/routes/score{"\n"}
                  <b>{'{ "risk": 15, "route": "B" }'}</b>
                </pre>
              </div>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

/* ---------- pricing ---------- */

function PricingSection() {
  const navigate = useNavigate();
  return (
    <section className="fs-pricing" id="pricing" aria-labelledby="fs-pricing-title">
      <div className="fs-shell">
        <div className="fs-pricing-head">
          <Reveal>
            <span className="fs-eyebrow">Pricing</span>
            <h2 className="fs-h2" id="fs-pricing-title">
              Free for the drive.
              <br />
              Pro for the job.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              Every tier ships with the full risk engine. Pro adds the commercial muscle &mdash; hazmat, fleet,
              and corridor ops.
            </p>
          </Reveal>
        </div>
        <div className="fs-tiers">
          <Reveal delay={0}>
            <div className="fs-tier">
              <span className="fs-tier-name">Free</span>
              <strong className="fs-tier-for">Everyday drivers</strong>
              <div className="fs-tier-price">
                <strong>$0</strong>
                <span>/ FOREVER</span>
              </div>
              <ul>
                <li><Check size={15} /> Car &amp; van trip planning</li>
                <li><Check size={15} /> Live weather alerts</li>
                <li><Check size={15} /> Route risk scores</li>
                <li><Check size={15} /> 3 saved route watches</li>
                <li><Check size={15} /> Daily alert digest</li>
              </ul>
              <button className="fs-btn fs-btn-outline fs-btn-block" onClick={() => navigate("/app")}>
                Start free
              </button>
            </div>
          </Reveal>
          <Reveal delay={130}>
            <div className="fs-tier fs-tier-pro">
              <span className="fs-tier-badge">FOR WORK</span>
              <span className="fs-tier-name">Pro</span>
              <strong className="fs-tier-for">Truck, van &amp; fleet</strong>
              <div className="fs-tier-price">
                <strong>$29</strong>
                <span>/ VEHICLE / MO</span>
              </div>
              <ul>
                <li><Check size={15} /> Everything in Free</li>
                <li><Check size={15} /> Truck routing + FHWA hazmat rules</li>
                <li><Check size={15} /> Unlimited corridor watches</li>
                <li><Check size={15} /> Live alert SMS + voice calls</li>
                <li><Check size={15} /> Fleet dispatch board + multi-stop optimization</li>
                <li><Check size={15} /> API + TMS webhooks</li>
              </ul>
              <button className="fs-btn fs-btn-primary fs-btn-block" onClick={() => navigate("/app/pricing")}>
                Start 14-day trial
              </button>
            </div>
          </Reveal>
        </div>
        <Reveal delay={120}>
          <p className="fs-pricing-note">
            Checkout opens soon &mdash; early drivers lock launch pricing &middot; fleets:{" "}
            <a href="mailto:hello@freightscaler.com">hello@freightscaler.com</a>
          </p>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- cta + footer ---------- */

function CtaBand() {
  const navigate = useNavigate();
  return (
    <section className="fs-cta" aria-labelledby="fs-cta-title">
      <svg className="fs-cta-routes" viewBox="0 0 1440 320" preserveAspectRatio="none" aria-hidden="true">
        <path d="M-40 260 C 300 220, 520 120, 820 130 S 1300 60, 1500 90" />
        <path d="M-40 300 C 360 280, 640 200, 940 210 S 1360 150, 1500 170" />
        <path d="M-40 60 C 260 90, 560 40, 860 60 S 1320 20, 1500 40" />
      </svg>
      <div className="fs-shell fs-cta-inner">
        <Reveal>
          <h2 id="fs-cta-title">Your next trip has a weather window. Find it.</h2>
        </Reveal>
        <Reveal delay={140} className="fs-cta-right">
          <button className="fs-btn fs-btn-inverse" onClick={() => navigate("/app/map")}>
            Open FreightScaler <ArrowRight size={16} />
          </button>
          <span className="fs-cta-note">NO CARD REQUIRED &middot; FREE FOR EVERY DRIVER</span>
        </Reveal>
      </div>
    </section>
  );
}

function LandingFooter() {
  const navigate = useNavigate();
  return (
    <footer className="fs-footer">
      <div className="fs-shell">
        <div className="fs-footer-grid">
          <div className="fs-footer-brand">
            <Brand />
            <p>Weather-aware route planning for car, van, and truck drivers. freightscaler.com</p>
            <button className="fs-status-chip" onClick={() => navigate("/app/status")}>
              <i className="fs-live-dot fs-live-dot-green" /> ALL SYSTEMS NOMINAL
            </button>
          </div>
          <nav aria-label="Product">
            <strong>Product</strong>
            <button onClick={() => navigate("/app/map")}>Route planner</button>
            <button onClick={() => navigate("/app/alerts")}>Live alerts</button>
            <button onClick={() => navigate("/app/dashboard")}>Dashboard</button>
            <button onClick={() => navigate("/app/pricing")}>Pricing</button>
          </nav>
          <nav aria-label="Company">
            <strong>Company</strong>
            <a href="#top">About</a>
            <a href="mailto:hello@freightscaler.com">Contact</a>
            <a href="https://freightscaler.com" target="_blank" rel="noreferrer">
              freightscaler.com
            </a>
          </nav>
          <nav aria-label="Resources">
            <strong>Resources</strong>
            <button onClick={() => navigate("/app/status")}>Operational status</button>
            <button onClick={() => navigate("/app/usage")}>Usage</button>
            <button onClick={() => navigate("/app/legal/terms")}>Terms</button>
            <button onClick={() => navigate("/app/legal/privacy")}>Privacy</button>
          </nav>
        </div>
        <div className="fs-footer-bottom">
          <span>&copy; 2026 FreightScaler &middot; freightscaler.com</span>
          <em>WEATHER DATA: NWS / NOAA &middot; RISK ENGINE v2.4</em>
        </div>
      </div>
    </footer>
  );
}

/* ---------- page ---------- */

export default function LandingPage() {
  useEffect(() => {
    window.scrollTo(0, 0);
  }, []);
  return (
    <div className="fs-page">
      <LandingHeader />
      <main>
        <HeroSection />
        <MetricStrip />
        <ThresholdSection />
        <HowSection />
        <CompareSection />
        <AssistantSection />
        <FeaturesSection />
        <PricingSection />
        <CtaBand />
      </main>
      <LandingFooter />
    </div>
  );
}
