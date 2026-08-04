"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Button, Input } from "@/components/ui";
import { NetworkError } from "@/lib/api/client";
import { authApi, platformApi } from "@/lib/api/endpoints";
import { useQuery } from "@tanstack/react-query";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Whether mail is actually deliverable in this environment. Without it the
  // confirmation would claim an email was sent when none was.
  const { data: platform } = useQuery({
    queryKey: ["platform"],
    queryFn: platformApi.info,
    staleTime: Infinity,
  });

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      await authApi.forgotPassword(email);
      // The endpoint responds identically for known and unknown addresses, and
      // so does this screen — otherwise the UI would reintroduce the account
      // enumeration the API is careful to avoid.
      setSubmitted(true);
    } catch (caught) {
      setError(
        caught instanceof NetworkError
          ? caught.message
          : "Could not submit the request. Please try again.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Check your email</h1>
        <p className="mt-3 text-[13px] leading-relaxed text-content-secondary">
          If an account exists for <span className="text-content-primary">{email}</span>, a password
          reset link has been generated. The link expires in 30 minutes.
        </p>

        {platform && !platform.emailDeliveryEnabled && (
          <div className="mt-5 rounded-md border border-status-moderate/25 bg-status-moderate-bg px-3.5 py-3 text-[13px] text-status-moderate">
            <p className="font-medium">No mail provider is configured in this environment.</p>
            <p className="mt-1 opacity-90">
              No email was actually sent. The reset link was written to the backend application
              log — see the console running the API.
            </p>
          </div>
        )}

        <Link
          href="/login"
          className="mt-6 inline-block text-[13px] text-accent transition-colors hover:text-accent-hover"
        >
          Back to sign in
        </Link>
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight">Reset your password</h1>
      <p className="mt-1.5 text-[13px] text-content-tertiary">
        Enter your email address and we will generate a reset link.
      </p>

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
          placeholder="you@organisation.com"
        />

        <Button type="submit" loading={submitting} fullWidth size="lg">
          {submitting ? "Sending" : "Send reset link"}
        </Button>
      </form>

      <p className="mt-6 text-center text-[13px] text-content-tertiary">
        <Link href="/login" className="text-accent transition-colors hover:text-accent-hover">
          Back to sign in
        </Link>
      </p>
    </div>
  );
}
