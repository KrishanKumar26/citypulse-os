"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, Input } from "@/components/ui";
import { ApiRequestError, NetworkError } from "@/lib/api/client";
import { authApi } from "@/lib/api/endpoints";

/**
 * Mirrors the server-side policy in StrongPasswordValidator. This is guidance
 * for the user, not a control — the API rejects a weak password regardless of
 * what happens here (docs/SECURITY.md §2).
 */
const PASSWORD_RULES = [
  { label: "At least 12 characters", test: (value: string) => value.length >= 12 },
  { label: "An uppercase letter", test: (value: string) => /[A-Z]/.test(value) },
  { label: "A lowercase letter", test: (value: string) => /[a-z]/.test(value) },
  { label: "A digit", test: (value: string) => /\d/.test(value) },
  { label: "A symbol", test: (value: string) => /[^A-Za-z0-9\s]/.test(value) },
];

export default function SignupPage() {
  const router = useRouter();

  const [form, setForm] = useState({ fullName: "", email: "", organization: "", password: "" });
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const passwordChecks = PASSWORD_RULES.map((rule) => ({
    label: rule.label,
    met: rule.test(form.password),
  }));

  function update(field: keyof typeof form, value: string) {
    setForm((previous) => ({ ...previous, [field]: value }));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      const result = await authApi.signup({
        email: form.email,
        password: form.password,
        fullName: form.fullName,
        organization: form.organization || undefined,
      });

      // The backend reports whether verification is required and whether email
      // is actually deliverable; the message is surfaced rather than assumed.
      if (result.emailVerificationRequired) {
        router.push(`/login?pending=1&message=${encodeURIComponent(result.message)}`);
      } else {
        router.push("/login?registered=1");
      }
    } catch (caught) {
      if (caught instanceof ApiRequestError) {
        setFieldErrors(caught.fieldErrors);
        setError(caught.isValidation ? "Check the highlighted fields." : caught.message);
      } else if (caught instanceof NetworkError) {
        setError(caught.message);
      } else {
        setError("Could not create the account. Please try again.");
      }
      setSubmitting(false);
    }
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight">Create an account</h1>
      <p className="mt-1.5 text-[13px] text-content-tertiary">
        New accounts receive read-only access. An administrator grants further permissions.
      </p>

      <form onSubmit={handleSubmit} className="mt-6 space-y-4" noValidate>
        {error && (
          <div role="alert" className="rounded-md border border-status-critical/25 bg-status-critical-bg px-3.5 py-2.5 text-[13px] text-status-critical">
            {error}
          </div>
        )}

        <Input
          label="Full name"
          name="fullName"
          autoComplete="name"
          required
          value={form.fullName}
          onChange={(event) => update("fullName", event.target.value)}
          error={fieldErrors.fullName}
          placeholder="Alex Mercer"
        />

        <Input
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          value={form.email}
          onChange={(event) => update("email", event.target.value)}
          error={fieldErrors.email}
          placeholder="you@organisation.com"
        />

        <Input
          label="Organisation"
          name="organization"
          autoComplete="organization"
          value={form.organization}
          onChange={(event) => update("organization", event.target.value)}
          error={fieldErrors.organization}
          hint="Optional"
          placeholder="City Transport Authority"
        />

        <div>
          <Input
            label="Password"
            type="password"
            name="password"
            autoComplete="new-password"
            required
            value={form.password}
            onChange={(event) => update("password", event.target.value)}
            error={fieldErrors.password}
            placeholder="••••••••••••"
          />

          {form.password.length > 0 && (
            <ul className="mt-2.5 grid grid-cols-2 gap-x-3 gap-y-1" aria-label="Password requirements">
              {passwordChecks.map((check) => (
                <li
                  key={check.label}
                  className={`flex items-center gap-1.5 text-[12px] ${
                    check.met ? "text-status-normal" : "text-content-tertiary"
                  }`}
                >
                  <span aria-hidden="true">{check.met ? "✓" : "○"}</span>
                  <span>{check.label}</span>
                  <span className="sr-only">{check.met ? "met" : "not met"}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <Button type="submit" loading={submitting} fullWidth size="lg">
          {submitting ? "Creating account" : "Create account"}
        </Button>
      </form>

      <p className="mt-6 text-center text-[13px] text-content-tertiary">
        Already have an account?{" "}
        <Link href="/login" className="text-accent transition-colors hover:text-accent-hover">
          Sign in
        </Link>
      </p>
    </div>
  );
}
