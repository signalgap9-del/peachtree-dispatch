import {
  ArrowRight,
  ArrowUpRight,
  Check,
  CheckCircle2,
  CloudRain,
  ListOrdered,
  Mail,
  MapPin,
  Menu,
  Mic,
  Radar,
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
  { href: "#dispatcher", label: "Dispatcher AI" },
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
          <small>Lane swap</small>
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
            <strong>SEA &rarr; MIA</strong> &middot; LOAD #4821 &middot; 2 LANES SCORED
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

          {/* lane A - fastest, risky */}
          <path
            className="fs-route"
            pathLength={1}
            d="M56 74 C 160 150, 240 210, 320 290 C 380 350, 420 380, 470 405 C 530 435, 590 448, 652 452"
            stroke="#D93025"
            strokeWidth="3.5"
          />
          {/* lane B - lower risk */}
          <path
            className="fs-route fs-route-b"
            pathLength={1}
            d="M56 74 C 170 52, 320 56, 430 92 C 530 125, 596 180, 620 250 C 644 320, 650 390, 652 452"
            stroke="#188038"
            strokeWidth="3.5"
          />

          {/* lane labels */}
          <text className="fs-map-note" x="262" y="40" textAnchor="middle" fill="#188038">LANE B &middot; 4,039 MI</text>
          <text className="fs-map-note" x="128" y="178" textAnchor="middle" fill="#C5221F">LANE A &middot; 3,658 MI</text>

          {/* hazard chips along lane A */}
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
            <i className="fs-legend-line" style={{ background: "#188038" }} /> LANE B &middot; LOWER RISK &middot; 77.9 HRS
          </span>
          <span>
            <i className="fs-legend-line" style={{ background: "#D93025" }} /> LANE A &middot; FASTEST &middot; 69.4 HRS
          </span>
        </div>
      </div>
      <div className="fs-float fs-float-delay">
        <span className="fs-float-icon" style={{ background: "var(--fs-red-soft)", color: "var(--fs-red)" }}>
          <TriangleAlert size={17} />
        </span>
        <span>
          <small>Climate delay &middot; Lane A</small>
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
              <i className="fs-live-dot" /> 42 active alerts on monitored corridors
            </span>
          </Reveal>
          <Reveal delay={90}>
            <h1 className="fs-h1" id="fs-hero-title">
              Know which
              <br />
              corridor is <em>safe</em>
              <br />
              before you dispatch.
            </h1>
          </Reveal>
          <Reveal delay={180}>
            <p className="fs-hero-sub">
              FreightScaler scores every lane against live NWS / NOAA alerts, forecast models, and historical
              climate delay &mdash; so dispatch sends trucks down the miles that hold schedule.
            </p>
          </Reveal>
          <Reveal delay={260}>
            <div className="fs-hero-cta">
              <button className="fs-btn fs-btn-primary" onClick={() => navigate("/app/map")}>
                Plan a corridor <ArrowRight size={16} />
              </button>
              <a className="fs-btn fs-btn-outline" href="#compare">
                See the SEA &rarr; MIA call
              </a>
            </div>
          </Reveal>
          <Reveal delay={340}>
            <div className="fs-hero-stats">
              <div>
                <strong>3,658 mi</strong>
                <span>Fastest lane</span>
              </div>
              <div>
                <strong>4,039 mi</strong>
                <span>Lower-risk lane</span>
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
          <span>Active corridor alerts right now</span>
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
          <span>Climate delay flagged on the SEA &rarr; MIA fast lane</span>
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
              One clean corridor.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              No weather tab, no radar app, no gut feel. FreightScaler folds risk straight into the routing.
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
              <h3>Plan the run</h3>
              <p>Drop origin, destination, cargo, and your window &mdash; or type it the way you&rsquo;d call it in over the radio.</p>
              <div className="fs-step-mock">
                <div className="fs-step-input">
                  <MapPin size={13} /> SEA <ArrowRight size={12} /> MIA
                </div>
                <div className="fs-step-chips">
                  <i>HAZMAT</i>
                  <i>42,000 LB</i>
                  <i>DEPART 08:00</i>
                </div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-step" delay={140}>
            <span className="fs-step-badge">02</span>
            <div className="fs-step-card">
              <h3>Compare the risk</h3>
              <p>Every lane gets a 0&ndash;100 score built from live alerts, forecast models, and historical climate delay.</p>
              <div className="fs-step-mock">
                <div className="fs-step-lane">
                  <b>LANE A</b>
                  <span className="fs-step-bar">
                    <i style={{ "--w": "58%", background: "var(--fs-red)" } as CSSProperties} />
                  </span>
                  <em style={{ color: "var(--fs-red)" }}>58</em>
                </div>
                <div className="fs-step-lane">
                  <b>LANE B</b>
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
              <h3>Monitor the corridor</h3>
              <p>Watches stay open until the truck parks. If weather moves on your lane, dispatch knows first.</p>
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
            <span className="fs-eyebrow">Route call &middot; SEA &rarr; MIA &middot; Load #4821</span>
            <h2 className="fs-h2" id="fs-compare-title">
              The fastest lane isn&rsquo;t always the cheapest lane.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">
              Same 42,000 lb load, same deadline. Two ways to run it &mdash; here&rsquo;s the call FreightScaler put in
              front of dispatch.
            </p>
          </Reveal>
        </div>
        <div className="fs-lanes">
          <Reveal delay={0}>
            <article className="fs-lane-card fs-lane-a">
              <div className="fs-lane-tags">
                <span className="fs-lane-tag">LANE A &middot; FASTEST</span>
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
                  <span>Climate delay</span>
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
                10.5 hours of climate delay baked into the &ldquo;fastest&rdquo; ETA.
              </p>
            </article>
          </Reveal>
          <Reveal delay={140}>
            <article className="fs-lane-card fs-lane-b">
              <div className="fs-lane-tags">
                <span className="fs-lane-tag">LANE B &middot; LOWER RISK</span>
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
                  <span>Climate delay</span>
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
                381 extra miles that dodge the storm entirely, hold the delivery window, and keep the load &mdash; and
                the driver &mdash; out of the claim file.
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
              <b>THE CALL: LANE B &mdash; I-40 E &rarr; I-81 S</b>
              <span>8.5 extra hours of driving buys back 10.5 hours of storm delay and clears the Gulf track entirely.</span>
            </p>
            <em>RISK ENGINE v2.4 &middot; SCORED 06:12 CT</em>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

/* ---------- dispatcher AI ---------- */

function DispatcherSection() {
  const navigate = useNavigate();
  return (
    <section className="fs-dispatch" id="dispatcher" aria-labelledby="fs-dispatch-title">
      <div className="fs-shell fs-dispatch-grid">
        <div>
          <Reveal>
            <span className="fs-eyebrow">AI dispatcher</span>
            <h2 className="fs-h2" id="fs-dispatch-title">
              Talk to it like you&rsquo;d talk to a driver.
            </h2>
            <p className="fs-lede">
              No forms, no drop-downs. Type the run the way you&rsquo;d call it in &mdash; FreightScaler reads the
              constraints, checks hazmat rules, and hands back a scored lane.
            </p>
          </Reveal>
          <Reveal delay={140}>
            <ul className="fs-checklist">
              <li>
                <i><Check size={13} /></i> Reads plain-English constraints
              </li>
              <li>
                <i><Check size={13} /></i> Applies FHWA hazmat routing rules
              </li>
              <li>
                <i><Check size={13} /></i> Returns a scored lane in about a second
              </li>
            </ul>
          </Reveal>
          <Reveal delay={220}>
            <button className="fs-dispatch-link" onClick={() => navigate("/app")}>
              Open the dispatcher <ArrowRight size={16} />
            </button>
          </Reveal>
        </div>
        <Reveal delay={160}>
          <div className="fs-chat" role="img" aria-label="Example dispatcher conversation: a natural language request turned into a scored route">
            <div className="fs-chat-bar">
              <span>
                <i className="fs-live-dot fs-live-dot-green" /> DISPATCH &middot; LOAD #4821
              </span>
              <em>06:04 CT</em>
            </div>
            <div className="fs-chat-body">
              <Reveal delay={80}>
                <p className="fs-msg-user">
                  Seattle to Miami, truck with hazmat, avoid highways, arrive before the storm
                </p>
              </Reveal>
              <Reveal delay={240}>
                <div className="fs-msg-ai">
                  <p>Copy. Locking constraints:</p>
                  <div className="fs-constraints">
                    <i>HAZMAT=TRUE</i>
                    <i>DEPART=08:00</i>
                    <i>AVOID=HIGHWAYS</i>
                    <i>TARGET=MIN RISK</i>
                  </div>
                </div>
              </Reveal>
              <Reveal delay={420}>
                <div className="fs-chat-route">
                  <strong>Lane B &middot; I-40 E &rarr; I-81 S</strong>
                  <b>4,039 MI &middot; 77.9 HRS &middot; RISK 15</b>
                  <p>Clears the Gulf storm track. Hazmat-legal the whole way, arrival 14 hours ahead of the cell.</p>
                  <em>SOURCES: NWS &middot; NOAA &middot; FHWA HAZMAT</em>
                </div>
              </Reveal>
              <Reveal delay={560}>
                <div className="fs-chat-status">
                  <i /> SOLVED IN 1.2 S &middot; 2 LANES SCORED
                </div>
              </Reveal>
            </div>
            <div className="fs-chat-input">
              <span>Message the dispatcher&hellip;</span>
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

function FeaturesSection() {
  return (
    <section className="fs-features" id="features" aria-labelledby="fs-features-title">
      <div className="fs-shell">
        <Reveal>
          <span className="fs-eyebrow">Built for freight</span>
          <h2 className="fs-h2" id="fs-features-title">
            Everything dispatch needs,
            <br />
            nothing it has to babysit.
          </h2>
        </Reveal>
        <div className="fs-bento">
          <Reveal className="fs-cell-haz" delay={0}>
            <div className="fs-cell">
              <span className="fs-cell-icon fs-cell-icon-red">
                <ShieldAlert size={20} />
              </span>
              <h3>Hazmat-aware routing</h3>
              <p>
                Routes around restricted corridors, tunnels, and population-dense zones &mdash; and keeps the
                compliance trail attached to the load.
              </p>
              <div className="fs-cell-visual">
                <HazmatVisual />
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-mon" delay={120}>
            <div className="fs-cell">
              <span className="fs-cell-icon">
                <Radar size={20} />
              </span>
              <h3>24/7 corridor monitoring</h3>
              <p>Every lane your fleet runs, watched against live NWS and NOAA feeds &mdash; before weather becomes a claim.</p>
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
          <Reveal className="fs-cell-sto" delay={180}>
            <div className="fs-cell">
              <span className="fs-cell-icon fs-cell-icon-amber">
                <ListOrdered size={20} />
              </span>
              <h3>Multi-stop optimization</h3>
              <p>Sequence 12 stops against the weather window, not around it.</p>
              <div className="fs-cell-visual">
                <div className="fs-step-input">SEA 08:00 &rarr; PDX 14:30 &rarr; BOI 09:12+1</div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-dig" delay={240}>
            <div className="fs-cell">
              <span className="fs-cell-icon fs-cell-icon-green">
                <Mail size={20} />
              </span>
              <h3>Alert digests</h3>
              <p>A 06:00 corridor digest to SMS and email &mdash; before the first truck rolls.</p>
              <div className="fs-cell-visual">
                <div className="fs-step-input">DAILY 06:00 &middot; SMS + EMAIL</div>
              </div>
            </div>
          </Reveal>
          <Reveal className="fs-cell-flt" delay={160}>
            <div className="fs-cell">
              <span className="fs-cell-icon">
                <Truck size={20} />
              </span>
              <h3>Fleet dispatch board</h3>
              <p>Every power unit, every lane, one board &mdash; risk per truck, not per spreadsheet.</p>
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
              <span className="fs-cell-icon fs-cell-icon-amber">
                <Webhook size={20} />
              </span>
              <h3>API + TMS hooks</h3>
              <p>Score corridors from your own TMS with a REST API and outbound webhooks.</p>
              <div className="fs-cell-visual">
                <pre className="fs-code">
                  <i>$</i> POST /v1/corridors/score{"\n"}
                  <b>{'{ "risk": 15, "lane": "B" }'}</b>
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
              Priced per truck.
              <br />
              Not per surprise.
            </h2>
          </Reveal>
          <Reveal delay={120}>
            <p className="fs-lede">Start free, scale when the fleet does. Every tier ships with the full risk engine.</p>
          </Reveal>
        </div>
        <div className="fs-tiers">
          <Reveal delay={0}>
            <div className="fs-tier">
              <span className="fs-tier-name">Owner-Operator</span>
              <strong className="fs-tier-for">One truck, own lanes</strong>
              <div className="fs-tier-price">
                <strong>$0</strong>
                <span>/ FOREVER</span>
              </div>
              <ul>
                <li><Check size={15} /> 1 power unit</li>
                <li><Check size={15} /> 3 corridor watches</li>
                <li><Check size={15} /> Daily alert digest</li>
                <li><Check size={15} /> Standard route scoring</li>
              </ul>
              <button className="fs-btn fs-btn-outline fs-btn-block" onClick={() => navigate("/app")}>
                Start free
              </button>
            </div>
          </Reveal>
          <Reveal delay={130}>
            <div className="fs-tier fs-tier-pro">
              <span className="fs-tier-badge">MOST DISPATCHED</span>
              <span className="fs-tier-name">Pro</span>
              <strong className="fs-tier-for">Regional dispatch teams</strong>
              <div className="fs-tier-price">
                <strong>$29</strong>
                <span>/ TRUCK / MO</span>
              </div>
              <ul>
                <li><Check size={15} /> Up to 25 power units</li>
                <li><Check size={15} /> Unlimited corridor watches</li>
                <li><Check size={15} /> Live alert SMS + voice calls</li>
                <li><Check size={15} /> Hazmat-aware routing</li>
                <li><Check size={15} /> AI dispatcher + multi-stop optimization</li>
              </ul>
              <button className="fs-btn fs-btn-primary fs-btn-block" onClick={() => navigate("/app/pricing")}>
                Start 14-day trial
              </button>
            </div>
          </Reveal>
          <Reveal delay={260}>
            <div className="fs-tier">
              <span className="fs-tier-name">Fleet</span>
              <strong className="fs-tier-for">Dedicated dispatch ops</strong>
              <div className="fs-tier-price">
                <strong>Custom</strong>
                <span>/ PER DISPATCHER SEAT</span>
              </div>
              <ul>
                <li><Check size={15} /> Volume pricing per dispatcher</li>
                <li><Check size={15} /> API + TMS integration</li>
                <li><Check size={15} /> SSO / SAML</li>
                <li><Check size={15} /> Dedicated freight-ops support</li>
              </ul>
              <a className="fs-btn fs-btn-outline fs-btn-block" href="mailto:hello@freightscaler.com">
                Talk to freight ops
              </a>
            </div>
          </Reveal>
        </div>
        <Reveal delay={120}>
          <p className="fs-pricing-note">
            Checkout opens soon &mdash; early fleets lock launch pricing &middot;{" "}
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
          <h2 id="fs-cta-title">Your next load has a weather window. Find it.</h2>
        </Reveal>
        <Reveal delay={140} className="fs-cta-right">
          <button className="fs-btn fs-btn-inverse" onClick={() => navigate("/app/map")}>
            Open FreightScaler <ArrowRight size={16} />
          </button>
          <span className="fs-cta-note">NO CARD REQUIRED &middot; FREE FOR OWNER-OPERATORS</span>
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
            <p>Weather-aware freight route planning for drivers, dispatchers, and fleet ops. freightscaler.com</p>
            <button className="fs-status-chip" onClick={() => navigate("/app/status")}>
              <i className="fs-live-dot fs-live-dot-green" /> ALL SYSTEMS NOMINAL
            </button>
          </div>
          <nav aria-label="Product">
            <strong>Product</strong>
            <button onClick={() => navigate("/app/map")}>Route planner</button>
            <button onClick={() => navigate("/app/alerts")}>Corridor alerts</button>
            <button onClick={() => navigate("/app/dashboard")}>Dispatch dashboard</button>
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
        <HowSection />
        <CompareSection />
        <DispatcherSection />
        <FeaturesSection />
        <PricingSection />
        <CtaBand />
      </main>
      <LandingFooter />
    </div>
  );
}
