"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Input,
  LoadingState,
  PageHeader,
  cn,
} from "@/components/ui";
import { apiKeyApi } from "@/lib/api/endpoints";
import type { ApiKeyCreated, ApiKeySummary } from "@/lib/api/types";

/**
 * API keys for programmatic access (PRD §22).
 *
 * <p>Two things this screen must get right, because both are easy to get wrong
 * in a way nobody notices until it matters.
 *
 * The secret appears once. Nothing stores it, so no reload, no support request
 * and no database query can produce it again — and the interface has to say that
 * plainly at the moment it is shown, not in documentation the reader will not
 * open. A dismissable panel that quietly disappears would lose someone's key.
 *
 * The scope list offers only what the caller holds. Showing every permission and
 * failing on submit would put controls on screen whose only possible outcome is
 * an error, and would imply the platform might grant them.
 */

function relative(iso: string | null): string {
  if (iso === null) return "never";
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 90) return "just now";
  if (seconds < 5400) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 172800) return `${Math.round(seconds / 3600)}h ago`;
  return `${Math.round(seconds / 86400)}d ago`;
}

export default function ApiKeysPage() {
  const queryClient = useQueryClient();
  const [issued, setIssued] = useState<ApiKeyCreated | null>(null);
  const [copied, setCopied] = useState(false);

  const keysQuery = useQuery({ queryKey: ["api-keys"], queryFn: () => apiKeyApi.list() });
  const scopesQuery = useQuery({ queryKey: ["api-key-scopes"], queryFn: () => apiKeyApi.scopes() });

  const revoke = useMutation({
    mutationFn: (id: string) => apiKeyApi.revoke(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["api-keys"] }),
  });

  const keys = keysQuery.data ?? [];

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="API Management"
        subtitle={<>Credentials for programmatic access. A key carries only the permissions you hold.</>}
      />

      {issued && (
        <SecretPanel
          issued={issued}
          copied={copied}
          onCopy={async () => {
            await navigator.clipboard.writeText(issued.secret);
            setCopied(true);
          }}
          onDismiss={() => {
            setIssued(null);
            setCopied(false);
          }}
        />
      )}

      <CreateKey
        grantable={scopesQuery.data?.grantable ?? []}
        loadingScopes={scopesQuery.isLoading}
        onCreated={(created) => {
          setIssued(created);
          setCopied(false);
          void queryClient.invalidateQueries({ queryKey: ["api-keys"] });
        }}
      />

      <Card className="overflow-hidden">
        <CardHeader
          title="Your keys"
          description="The secret is never listed — it is not stored. The prefix identifies a key without revealing it."
        />
        {keysQuery.isError ? (
          <ErrorState
            title="Could not load keys"
            message={keysQuery.error instanceof Error ? keysQuery.error.message : "Unavailable."}
            onRetry={() => void keysQuery.refetch()}
          />
        ) : keysQuery.isLoading ? (
          <LoadingState label="Loading keys" rows={3} />
        ) : keys.length === 0 ? (
          <EmptyState
            title="No keys yet"
            description="Create one above. It will be shown once and cannot be recovered afterwards."
          />
        ) : (
          <ul className="divide-y divide-line-subtle">
            {keys.map((key) => (
              <KeyRow
                key={key.id}
                apiKey={key}
                revoking={revoke.isPending && revoke.variables === key.id}
                onRevoke={() => revoke.mutate(key.id)}
              />
            ))}
          </ul>
        )}
      </Card>

      <p className="text-[11px] leading-relaxed text-content-tertiary">
        Keys authenticate with an <code className="text-content-secondary">X-API-Key</code> header,
        not <code className="text-content-secondary">Authorization</code>. A key carries the scopes
        it was issued with — frozen at creation, so it does not gain authority if your roles are
        widened later — and no roles at all, which keeps anything gated on a role reachable only
        from a signed-in session.
      </p>
    </div>
  );
}

/**
 * The secret, shown once.
 *
 * <p>Not dismissable by clicking away, and it says why it cannot be shown again
 * before it says anything else. Losing a key to a stray click is a support
 * request and a rotation; a sentence prevents both.
 */
function SecretPanel({
  issued,
  copied,
  onCopy,
  onDismiss,
}: {
  issued: ApiKeyCreated;
  copied: boolean;
  onCopy: () => void;
  onDismiss: () => void;
}) {
  return (
    <Card className="border-accent/40">
      <CardHeader
        title="Copy this key now"
        description="It is not stored anywhere. This is the only time it can be shown — if it is lost, revoke the key and issue another."
      />
      <div className="px-5 py-4">
        <div className="flex flex-wrap items-center gap-2">
          <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-line-default bg-surface-inset px-3 py-2.5 font-mono text-[12px] text-content-primary">
            {issued.secret}
          </code>
          <Button variant={copied ? "secondary" : "primary"} size="sm" onClick={onCopy}>
            {copied ? "Copied" : "Copy"}
          </Button>
        </div>

        <p className="mt-3 text-[12px] text-content-secondary">
          <span className="font-medium text-content-primary">{issued.key.name}</span> ·{" "}
          {issued.key.scopes.join(", ")}
        </p>

        <div className="mt-4">
          <Button variant="secondary" size="sm" onClick={onDismiss} disabled={!copied}>
            {copied ? "Done" : "Copy the key first"}
          </Button>
        </div>
      </div>
    </Card>
  );
}

function CreateKey({
  grantable,
  loadingScopes,
  onCreated,
}: {
  grantable: string[];
  loadingScopes: boolean;
  onCreated: (created: ApiKeyCreated) => void;
}) {
  const [name, setName] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expiresInDays, setExpiresInDays] = useState<string>("90");

  const create = useMutation({
    mutationFn: () =>
      apiKeyApi.create({
        name: name.trim(),
        scopes: [...selected],
        expiresInDays: expiresInDays === "" ? undefined : Number(expiresInDays),
      }),
    onSuccess: (created) => {
      onCreated(created);
      setName("");
      setSelected(new Set());
    },
  });

  const canSubmit = name.trim().length > 0 && selected.size > 0 && !create.isPending;

  return (
    <Card>
      <CardHeader
        title="Create a key"
        description="Only the permissions you hold are offered — a key cannot carry authority you do not have."
      />
      <div className="space-y-4 px-5 py-4">
        <Input
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Reporting job"
          hint="Required. An unnamed key cannot be revoked with any confidence about what will break."
        />

        <div>
          <span className="mb-2 block text-[13px] font-medium text-content-secondary">
            Permissions
          </span>
          {loadingScopes ? (
            <LoadingState label="Loading permissions" rows={2} />
          ) : grantable.length === 0 ? (
            <p className="text-[12px] text-content-tertiary">
              You hold no permissions that can be granted to a key.
            </p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {grantable.map((scope) => {
                const on = selected.has(scope);
                return (
                  <button
                    key={scope}
                    type="button"
                    aria-pressed={on}
                    onClick={() => {
                      const next = new Set(selected);
                      if (on) next.delete(scope);
                      else next.add(scope);
                      setSelected(next);
                    }}
                    className={cn(
                      "rounded-md border px-2 py-1 font-mono text-[11px] transition-colors",
                      on
                        ? "border-accent/40 bg-accent-subtle text-accent"
                        : "border-line-default text-content-secondary hover:bg-surface-hover hover:text-content-primary",
                    )}
                  >
                    {scope}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <Input
          label="Expires in (days)"
          type="number"
          min={1}
          value={expiresInDays}
          onChange={(e) => setExpiresInDays(e.target.value)}
          hint="Leave empty for a key that never expires — permitted, but the weaker choice."
          className="max-w-[180px]"
        />

        {create.isError && (
          <p className="text-[12px] text-status-critical">
            {create.error instanceof Error ? create.error.message : "Could not create the key."}
          </p>
        )}

        <Button onClick={() => create.mutate()} disabled={!canSubmit}>
          {create.isPending ? "Creating…" : "Create key"}
        </Button>
      </div>
    </Card>
  );
}

function KeyRow({
  apiKey,
  revoking,
  onRevoke,
}: {
  apiKey: ApiKeySummary;
  revoking: boolean;
  onRevoke: () => void;
}) {
  return (
    <li className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-5 py-3.5">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-[13px] text-content-primary">{apiKey.name}</span>
          {apiKey.active ? (
            <Badge level="normal">Active</Badge>
          ) : (
            // Says which of the reasons it is, not just that it is unusable.
            <Badge level="neutral">{apiKey.inactiveReason ?? "Inactive"}</Badge>
          )}
        </div>

        <code className="mt-1 block font-mono text-[11px] text-content-tertiary">
          {apiKey.keyPrefix}…
        </code>

        <div className="mt-1.5 flex flex-wrap gap-1">
          {apiKey.scopes.map((scope) => (
            <span
              key={scope}
              className="rounded border border-line-default px-1.5 py-px font-mono text-[10px] text-content-tertiary"
            >
              {scope}
            </span>
          ))}
        </div>

        <p className="mt-1.5 text-[10px] text-content-tertiary">
          Created {relative(apiKey.createdAt)}
          {apiKey.expiresAt && <> · expires {new Date(apiKey.expiresAt).toLocaleDateString()}</>}
          {" · "}
          {/* "never used" and "used long ago" are different, and the pair is how
              a key nobody needs any more is found. */}
          {apiKey.lastUsedAt === null ? "never used" : `last used ${relative(apiKey.lastUsedAt)}`}
          {apiKey.revokedReason && <> · {apiKey.revokedReason}</>}
        </p>
      </div>

      {apiKey.active && (
        <Button variant="secondary" size="sm" onClick={onRevoke} disabled={revoking}>
          {revoking ? "Revoking…" : "Revoke"}
        </Button>
      )}
    </li>
  );
}
