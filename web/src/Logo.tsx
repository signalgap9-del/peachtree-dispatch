interface LogoProps {
  /** Icon size in pixels. Defaults to 32. */
  size?: number;
  /** Show the "FreightScaler" wordmark beside the icon. */
  withWordmark?: boolean;
  className?: string;
}

/**
 * FreightScaler brand mark: hexagon (freight) + route curve + amber alert beacon.
 * Use in app header, landing page, and anywhere the brand appears inline.
 */
export default function Logo({ size = 32, withWordmark = false, className }: LogoProps) {
  return (
    <span
      className={className}
      style={{ display: "inline-flex", alignItems: "center", gap: withWordmark ? size * 0.3 : 0 }}
      aria-label={withWordmark ? "FreightScaler" : undefined}
    >
      <svg
        width={size}
        height={size}
        viewBox="0 0 64 64"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        role="img"
        aria-hidden={withWordmark ? true : false}
        aria-label={withWordmark ? undefined : "FreightScaler logo"}
      >
        <path d="M32 3 L57 17.5 L57 46.5 L32 61 L7 46.5 L7 17.5 Z" fill="#0B57D0" />
        <path
          d="M17 44 C 24 44, 26 36, 32 32 C 38 28, 40 22, 47 20"
          stroke="#FFFFFF"
          strokeWidth="7"
          strokeLinecap="round"
        />
        <circle cx="17" cy="44" r="5" fill="#FFFFFF" />
        <circle cx="47" cy="20" r="6.5" fill="#F9AB00" />
        <path d="M47 16.5 L50 21.5 L44 21.5 Z" fill="#0B57D0" />
      </svg>
      {withWordmark && (
        <span
          style={{
            fontFamily: "Inter, system-ui, sans-serif",
            fontWeight: 800,
            fontSize: size * 0.65,
            letterSpacing: "-0.02em",
            color: "#202124",
            lineHeight: 1,
            whiteSpace: "nowrap",
          }}
        >
          Freight<span style={{ color: "#0B57D0" }}>Scaler</span>
        </span>
      )}
    </span>
  );
}
