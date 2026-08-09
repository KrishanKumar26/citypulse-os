import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from "react";

/**
 * Design-system primitives (PRD §32).
 *
 * Kept in one file because the set is small and every component is a handful of
 * lines; splitting them across a dozen files would add navigation cost without
 * adding structure. This is revisited if any primitive grows real behaviour.
 */

export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(" ");
}

// --- Button -----------------------------------------------------------------

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "sm" | "md" | "lg";

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: "bg-accent text-white hover:bg-accent-hover disabled:bg-accent/40",
  secondary:
    "bg-surface-overlay text-content-primary border border-line-default hover:bg-surface-hover hover:border-line-strong",
  ghost: "text-content-secondary hover:text-content-primary hover:bg-surface-hover",
  danger: "bg-status-critical text-white hover:opacity-90",
};

const BUTTON_SIZES: Record<ButtonSize, string> = {
  sm: "h-8 px-3 text-[13px]",
  md: "h-10 px-4 text-sm",
  lg: "h-11 px-5 text-sm",
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  fullWidth?: boolean;
}

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  fullWidth = false,
  disabled,
  children,
  className,
  ...props
}: ButtonProps) {
  return (
    <button
      // Disabled while loading so a double submit cannot fire two requests.
      disabled={disabled || loading}
      // Announces the pending state to assistive technology, which a spinner alone does not.
      aria-busy={loading || undefined}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-md font-medium",
        "transition-colors duration-100 disabled:cursor-not-allowed disabled:opacity-60",
        BUTTON_VARIANTS[variant],
        BUTTON_SIZES[size],
        fullWidth && "w-full",
        className,
      )}
      {...props}
    >
      {loading && <Spinner />}
      {children}
    </button>
  );
}

function Spinner() {
  return (
    <svg className="h-3.5 w-3.5 animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
      <path className="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8v3a5 5 0 00-5 5H4z" />
    </svg>
  );
}

// --- Input ------------------------------------------------------------------

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
  /** Keeps the label for assistive technology while hiding it visually. */
  hideLabel?: boolean;
}

export function Input({ label, error, hint, id, className, hideLabel, ...props }: InputProps) {
  const inputId = id ?? `field-${label.toLowerCase().replace(/\s+/g, "-")}`;
  const describedBy = error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined;

  return (
    <div className="space-y-1.5">
      {/* Hidden visually, never removed. A search box in a toolbar has no room
          for a caption, but dropping the label would leave a screen reader with
          an unnamed field — so it is moved off-screen rather than deleted. */}
      <label
        htmlFor={inputId}
        className={cn(
          hideLabel ? "sr-only" : "block text-[13px] font-medium text-content-secondary",
        )}
      >
        {label}
      </label>
      <input
        id={inputId}
        // Both are needed: aria-invalid marks the field, aria-describedby points
        // a screen reader at the message explaining why.
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          "w-full rounded-md bg-surface-raised px-3 py-2 text-sm text-content-primary",
          "border placeholder:text-content-disabled",
          "transition-colors focus:outline-none focus:ring-2 focus:ring-accent/40",
          error ? "border-status-critical" : "border-line-default focus:border-accent",
          className,
        )}
        {...props}
      />
      {error && (
        <p id={`${inputId}-error`} role="alert" className="text-[13px] text-status-critical">
          {error}
        </p>
      )}
      {!error && hint && (
        <p id={`${inputId}-hint`} className="text-[13px] text-content-tertiary">
          {hint}
        </p>
      )}
    </div>
  );
}

// --- Card -------------------------------------------------------------------

export function Card({
  children,
  className,
  as: Tag = "div",
}: {
  children: ReactNode;
  className?: string;
  as?: "div" | "section" | "article";
}) {
  return (
    // Elevation as well as a border. On a near-black field a single hairline is
    // not a visible step, so every card read as flat regardless of what it held.
    <Tag
      className={cn(
        // surface-raised-edge, not a bare shadow: a fill plus a hairline gives an
        // edge but no form. The inset highlight along the top is what makes a
        // panel read as lifted off the page rather than drawn on it.
        "rounded-lg border border-line-subtle bg-surface-raised surface-raised-edge",
        className,
      )}
    >
      {children}
    </Tag>
  );
}

export function CardHeader({ title, description, action }: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    // Title and description were 14px and 13px — near enough that a card had no
    // internal hierarchy and the eye had nowhere to land. The gap is now wide
    // enough to be a hierarchy rather than a rounding difference.
    <div className="flex items-start justify-between gap-4 border-b border-line-subtle px-5 py-3.5">
      <div className="min-w-0">
        <h2 className="text-[15px] font-semibold leading-tight tracking-[-0.01em] text-content-primary">
          {title}
        </h2>
        {description && (
          <p className="mt-1 text-[11px] leading-relaxed text-content-tertiary">{description}</p>
        )}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

// --- Badge ------------------------------------------------------------------

/**
 * Status levels map to the four city-condition states in PRD §9. One scale
 * across alerts and conditions keeps a colour meaning one thing product-wide.
 */
export type StatusLevel = "normal" | "moderate" | "high" | "critical" | "info" | "neutral";

const BADGE_STYLES: Record<StatusLevel, string> = {
  normal: "bg-status-normal-bg text-status-normal border-status-normal/25",
  moderate: "bg-status-moderate-bg text-status-moderate border-status-moderate/25",
  high: "bg-status-high-bg text-status-high border-status-high/25",
  critical: "bg-status-critical-bg text-status-critical border-status-critical/25",
  info: "bg-info-bg text-info border-info/25",
  neutral: "bg-surface-overlay text-content-secondary border-line-default",
};

export function Badge({
  level = "neutral",
  children,
  className,
  title,
}: {
  level?: StatusLevel;
  children: ReactNode;
  className?: string;
  /** Long form of a badge that has to stay one word. Rendered as the native
      tooltip, so it supplements the label rather than carrying meaning the
      label lacks — a reader who never hovers still gets the state. */
  title?: string;
}) {
  return (
    <span
      title={title}
      className={cn(
        "inline-flex items-center gap-1.5 rounded border px-2 py-0.5 text-[11px] font-medium",
        BADGE_STYLES[level],
        className,
      )}
    >
      {children}
    </span>
  );
}

/**
 * Marks synthetic data wherever it appears (PRD §42, §31 of the execution
 * prompt). Deliberately a single component: labelling must be consistent, and
 * one implementation is one place to get it right.
 */
export function DemoDataBadge({ className }: { className?: string }) {
  return (
    <Badge level="moderate" className={className}>
      <span aria-hidden="true">●</span>
      DEMO DATA
    </Badge>
  );
}

// --- States (PRD §31: every data view implements all four) ------------------

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("skeleton rounded", className)} aria-hidden="true" />;
}

export function LoadingState({ label = "Loading", rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div className="space-y-2 p-5" role="status" aria-label={label}>
      {Array.from({ length: rows }).map((_, index) => (
        <Skeleton key={index} className="h-11 w-full" />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg border border-line-default bg-surface-overlay text-content-tertiary">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <path d="M8 12h8" />
        </svg>
      </div>
      <h3 className="text-sm font-medium text-content-primary">{title}</h3>
      <p className="mt-1 max-w-sm text-[13px] text-content-tertiary">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({
  title = "Something went wrong",
  message,
  onRetry,
}: {
  title?: string;
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center" role="alert">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg border border-status-critical/25 bg-status-critical-bg text-status-critical">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8v5M12 16h.01" />
        </svg>
      </div>
      <h3 className="text-sm font-medium text-content-primary">{title}</h3>
      <p className="mt-1 max-w-sm text-[13px] text-content-tertiary">{message}</p>
      {onRetry && (
        <Button variant="secondary" size="sm" className="mt-4" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}


/**
 * A measured quantity, presented so its weight matches its importance.
 *
 * The Command Center previously rendered eight identical tiles, so composite
 * risk — the number the whole page exists to communicate — looked exactly like
 * area coverage, which never changes. Emphasis is a property of the measurement
 * here rather than of the markup around it, so a screen cannot accidentally
 * give a trivia figure the same standing as the headline one.
 *
 * `value === null` is a first-class state, not an empty string. A dashboard
 * showing "0 km/h" where no reading exists reports a dead feed as gridlock. The
 * absence is drawn quietly but explicitly, and `absenceReason` says *why* when
 * the caller knows — "not measured" and "measured, just not in this window" are
 * different facts and only one of them is a problem.
 */
export function Metric({
  label,
  value,
  unit,
  level,
  note,
  emphasis = "default",
  absenceReason,
}: {
  label: string;
  /** Null renders as an explicit absence, never as zero. */
  value: string | null;
  unit?: string;
  level?: StatusLevel | null;
  /** Qualifies what the value covers — coverage, basis, or condition. */
  note?: string;
  emphasis?: "hero" | "default";
  /** Shown in place of the value when it is null. Defaults to "Not measured". */
  absenceReason?: string;
}) {
  const hero = emphasis === "hero";

  return (
    <div className={cn("flex flex-col", hero ? "gap-1.5" : "gap-1")}>
      {/* Labels recede so the value does not have to shout. The ratio between
          them is the hierarchy — making the number bigger while the label keeps
          pace changes nothing. */}
      <span
        className={cn(
          "font-medium uppercase tracking-[0.09em] text-content-tertiary",
          // A step apart, not half a step. These were 10.5 and 9.5, which is a
          // difference no reader can see — the label above a hero metric has to
          // look like a heading beside the ones that are not.
          hero ? "text-[11px]" : "text-[10px]",
        )}
      >
        {label}
      </span>

      {value === null ? (
        <span className={cn("text-content-disabled", hero ? "text-[15px]" : "text-[13px]")}>
          {absenceReason ?? "Not measured"}
        </span>
      ) : (
        <div className="flex items-baseline gap-1.5">
          <span
            className={cn(
              "tabular font-semibold tracking-tight",
              hero ? "text-[44px] leading-none" : "text-[27px] leading-none",
              level ? METRIC_TEXT[level] : "text-content-primary",
            )}
          >
            {value}
          </span>
          {unit && (
            <span className={cn("text-content-tertiary", hero ? "text-[13px]" : "text-[11px]")}>
              {unit}
            </span>
          )}
        </div>
      )}

      {note && <span className="text-[11px] leading-snug text-content-tertiary">{note}</span>}
    </div>
  );
}

const METRIC_TEXT: Record<StatusLevel, string> = {
  normal: "text-status-normal",
  moderate: "text-status-moderate",
  high: "text-status-high",
  critical: "text-status-critical",
  info: "text-info",
  neutral: "text-content-primary",
};

/**
 * The band a page opens with.
 *
 * Every screen was writing its own `<header>` — an 18px heading and a caption,
 * on the same plane as the cards below it, with four slightly different
 * paddings. Nothing anchored the top of a page, so the eye landed on whichever
 * card happened to be brightest rather than on where it was.
 *
 * One component so the title, its scale and the surface beneath it cannot drift
 * apart again — the same reason the severity palette and the map's colours are
 * now bound together rather than restated.
 *
 * `actions` sits opposite the title for controls that belong to the whole page:
 * a range picker, a filter, a badge about the page's data. Controls that belong
 * to one card stay on that card.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
  /** Rendered below the title row, inside the band — a status strip, usually. */
  children?: ReactNode;
}) {
  return (
    // Negative margins so the band spans the full width from inside a padded
    // page, rather than every page having to restructure its wrapper to let it
    // out. One component's problem instead of nine pages'.
    <header className="page-header-band -mx-5 -mt-5 mb-5 px-5 pb-4 pt-5">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h1 className="text-[26px] font-semibold leading-none tracking-[-0.02em] text-content-primary">
            {title}
          </h1>
          {subtitle && <p className="mt-2 text-[12px] text-content-tertiary">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children && <div className="mt-4">{children}</div>}
    </header>
  );
}
