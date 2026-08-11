"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { Button, Input } from "@/components/ui";
import { ApiRequestError, NetworkError } from "@/lib/api/client";
import { useSession } from "@/lib/auth/session";

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="h-64" />}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { signIn } = useSession();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  /**
   * Set once a sign-in has been waiting long enough to need explaining.
   *
   * The backend sleeps after fifteen minutes idle and a cold start was measured
   * at 104 seconds. Nothing said so, so the first sign-in after a quiet spell
   * was an unexplained spinner for a minute and a half — which reads as broken,
   * not as slow.
   */
  const [waking, setWaking] = useState(false);

  const sessionExpired = searchParams.get("reason") === "expired";
  const justRegistered = searchParams.get("registered") === "1";

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    setWaking(false);
    // Four seconds is past a warm sign-in, which measures about two, so this
    // only appears when something is actually wrong with the timing.
    const explainTheWait = setTimeout(() => setWaking(true), 4000);

    try {
      await signIn(email, password);
      router.push("/command-center");
    } catch (caught) {
      if (caught instanceof ApiRequestError) {
        setFieldErrors(caught.fieldErrors);
        // The backend returns one message for both an unknown address and a
        // wrong password. Showing it verbatim preserves that property.
        setError(caught.isValidation ? "Check the highlighted fields." : caught.message);
      } else if (caught instanceof NetworkError) {
        setError(caught.message);
      } else {
        setError("Sign-in failed. Please try again.");
      }
      setSubmitting(false);
    } finally {
      clearTimeout(explainTheWait);
      setWaking(false);
    }
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight">Sign in</h1>
      <p className="mt-1.5 text-[13px] text-content-tertiary">
        Access the CityPulse command centre.
      </p>

      {sessionExpired && (
        <div role="status" className="mt-5 rounded-md border border-status-moderate/25 bg-status-moderate-bg px-3.5 py-2.5 text-[13px] text-status-moderate">
          Your session expired. Please sign in again.
        </div>
      )}

      {justRegistered && (
        <div role="status" className="mt-5 rounded-md border border-status-normal/25 bg-status-normal-bg px-3.5 py-2.5 text-[13px] text-status-normal">
          Account created. Sign in to continue.
        </div>
      )}

      <form onSubmit={handleSubmit} className="mt-6 space-y-4" noValidate>
        {error && (
          <div role="alert" className="rounded-md border border-status-critical/25 bg-status-critical-bg px-3.5 py-2.5 text-[13px] text-status-critical">
            {error}
          </div>
        )}

        <Input
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={fieldErrors.email}
          placeholder="you@organisation.com"
        />

        <Input
          label="Password"
          type="password"
          name="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={fieldErrors.password}
          placeholder="••••••••••••"
        />

        <div className="flex justify-end">
          <Link href="/forgot-password" className="text-[13px] text-accent transition-colors hover:text-accent-hover">
            Forgot password?
          </Link>
        </div>

        <Button type="submit" loading={submitting} fullWidth size="lg">
          {submitting ? "Signing in" : "Sign in"}
        </Button>

        {/* Says what the wait is. A spinner with no explanation reads as broken;
            the same spinner with a reason reads as slow, which is what it is. */}
        {waking && (
          <p role="status" className="text-center text-[12px] text-content-tertiary">
            Waking the server — it sleeps when idle on the free tier, and can
            take about a minute.
          </p>
        )}
      </form>

      <p className="mt-6 text-center text-[13px] text-content-tertiary">
        No account?{" "}
        <Link href="/signup" className="text-accent transition-colors hover:text-accent-hover">
          Create one
        </Link>
      </p>
    </div>
  );
}
