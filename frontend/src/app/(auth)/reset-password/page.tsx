"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent } from "react";
import { Button, Input } from "@/components/ui";
import { ApiRequestError, NetworkError } from "@/lib/api/client";
import { authApi } from "@/lib/api/endpoints";

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={<div className="h-64" />}>
      <ResetPasswordForm />
    </Suspense>
  );
}

function ResetPasswordForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // Reaching this page without a token means the link was mistyped or truncated.
  // Say so plainly rather than presenting a form that cannot succeed.
  if (!token) {
    return (
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Invalid reset link</h1>
        <p className="mt-3 text-[13px] leading-relaxed text-content-secondary">
          This link is missing its reset token. Request a new one to continue.
        </p>
        <Link
          href="/forgot-password"
          className="mt-6 inline-block text-[13px] text-accent transition-colors hover:text-accent-hover"
        >
          Request a new link
        </Link>
      </div>
    );
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});

    if (password !== confirmation) {
      setFieldErrors({ confirmation: "Passwords do not match" });
      return;
    }

    setSubmitting(true);
    try {
      await authApi.resetPassword({ token: token!, newPassword: password });
      router.push("/login?reset=1");
    } catch (caught) {
      if (caught instanceof ApiRequestError) {
        setFieldErrors(caught.fieldErrors);
        setError(caught.isValidation ? "Check the highlighted fields." : caught.message);
      } else if (caught instanceof NetworkError) {
        setError(caught.message);
      } else {
        setError("Could not reset the password. Please try again.");
      }
      setSubmitting(false);
    }
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight">Set a new password</h1>
      <p className="mt-1.5 text-[13px] text-content-tertiary">
        Resetting your password signs you out of every active session.
      </p>

      <form onSubmit={handleSubmit} className="mt-6 space-y-4" noValidate>
        {error && (
          <div role="alert" className="rounded-md border border-status-critical/25 bg-status-critical-bg px-3.5 py-2.5 text-[13px] text-status-critical">
            {error}
          </div>
        )}

        <Input
          label="New password"
          type="password"
          name="newPassword"
          autoComplete="new-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={fieldErrors.newPassword}
          hint="At least 12 characters with upper case, lower case, a digit and a symbol"
          placeholder="••••••••••••"
        />

        <Input
          label="Confirm new password"
          type="password"
          name="confirmation"
          autoComplete="new-password"
          required
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
          error={fieldErrors.confirmation}
          placeholder="••••••••••••"
        />

        <Button type="submit" loading={submitting} fullWidth size="lg">
          {submitting ? "Updating" : "Update password"}
        </Button>
      </form>
    </div>
  );
}
