"use client";

import { useState, type FormEvent } from "react";
import { Badge, Button, Card, CardHeader, Input } from "@/components/ui";
import { ApiRequestError } from "@/lib/api/client";
import { authApi } from "@/lib/api/endpoints";
import { useSession } from "@/lib/auth/session";
import { formatInstant } from "@/lib/format";

/**
 * Settings: profile and password change. Both are fully implemented — the page
 * contains no control that does not work.
 */
export default function SettingsPage() {
  const { user } = useSession();

  if (!user) return null;

  return (
    <div className="mx-auto max-w-3xl p-5">
      <h1 className="text-lg font-semibold tracking-tight">Settings</h1>
      <p className="mt-1 text-[13px] text-content-tertiary">
        Your account, roles and effective permissions.
      </p>

      <div className="mt-5 space-y-5">
        <Card>
          <CardHeader title="Profile" />
          <dl className="divide-y divide-line-subtle">
            {[
              { label: "Name", value: user.fullName },
              { label: "Email", value: user.email },
              { label: "Organisation", value: user.organization ?? "Not set" },
              { label: "Status", value: user.status },
              { label: "Email verified", value: user.emailVerified ? "Yes" : "No" },
              { label: "Last sign-in", value: formatInstant(user.lastLoginAt) },
              { label: "Member since", value: formatInstant(user.createdAt) },
            ].map((row) => (
              <div key={row.label} className="flex items-center justify-between gap-4 px-5 py-2.5">
                <dt className="text-[13px] text-content-tertiary">{row.label}</dt>
                <dd className="text-[13px] text-content-primary">{row.value}</dd>
              </div>
            ))}
          </dl>
        </Card>

        <Card>
          <CardHeader
            title="Roles and permissions"
            description="Assigned by an administrator. The API enforces these independently of the interface."
          />
          <div className="space-y-4 px-5 py-4">
            <div>
              <div className="mb-2 text-[12px] text-content-tertiary">Roles</div>
              <div className="flex flex-wrap gap-1.5">
                {user.roles.map((role) => (
                  <Badge key={role} level="info">
                    {role.replace(/_/g, " ")}
                  </Badge>
                ))}
              </div>
            </div>
            <div>
              <div className="mb-2 text-[12px] text-content-tertiary">
                Effective permissions ({user.permissions.length})
              </div>
              <div className="flex flex-wrap gap-1.5">
                {user.permissions.map((permission) => (
                  <span
                    key={permission}
                    className="rounded border border-line-default bg-surface-overlay px-1.5 py-0.5 font-mono text-[11px] text-content-secondary"
                  >
                    {permission}
                  </span>
                ))}
              </div>
            </div>
          </div>
        </Card>

        <ChangePasswordCard />
      </div>
    </div>
  );
}

function ChangePasswordCard() {
  const { signOut } = useSession();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      await authApi.changePassword({ currentPassword, newPassword });
      setDone(true);
      // The backend revokes every session on a password change, so the current
      // one is already dead. Signing out keeps the UI honest instead of leaving
      // the user on a page whose next request will fail.
      setTimeout(() => void signOut(), 2000);
    } catch (caught) {
      if (caught instanceof ApiRequestError) {
        setFieldErrors(caught.fieldErrors);
        setError(caught.isValidation ? "Check the highlighted fields." : caught.message);
      } else {
        setError("Could not change the password. Please try again.");
      }
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <Card>
        <CardHeader title="Change password" />
        <div className="px-5 py-4" role="status">
          <p className="text-[13px] text-status-normal">
            Password updated. All sessions were revoked — signing you out now.
          </p>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        title="Change password"
        description="Changing your password signs you out of every active session."
      />
      <form onSubmit={handleSubmit} className="space-y-4 px-5 py-4" noValidate>
        {error && (
          <div role="alert" className="rounded-md border border-status-critical/25 bg-status-critical-bg px-3.5 py-2.5 text-[13px] text-status-critical">
            {error}
          </div>
        )}

        <Input
          label="Current password"
          type="password"
          autoComplete="current-password"
          required
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          error={fieldErrors.currentPassword}
        />

        <Input
          label="New password"
          type="password"
          autoComplete="new-password"
          required
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          error={fieldErrors.newPassword}
          hint="At least 12 characters with upper case, lower case, a digit and a symbol"
        />

        <Button type="submit" loading={submitting}>
          {submitting ? "Updating" : "Update password"}
        </Button>
      </form>
    </Card>
  );
}
