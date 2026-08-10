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
import { responseApi } from "@/lib/api/endpoints";
import type { ResponsePlan, ResponseStep } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";

/**
 * The Action Center: what we intend to do, and how far along it is.
 *
 * <p>The one thing this screen must never do is present a step the platform
 * supplied and a step a person wrote in the same voice. Exactly one line can
 * come from the system — the recommendedAction a rule attached when it fired —
 * and it is marked, because a plausible instruction assembled by software is
 * indistinguishable from a considered one and this is the screen where it gets
 * acted on rather than read.
 *
 * <p>Progress is per step, not per plan. Three of five done with the fourth
 * blocked is the ordinary state of an operational response, and a single status
 * badge cannot say it — so the bar and the counts carry it instead.
 */

const PRIORITY_STATUS = {
  CRITICAL: "critical",
  HIGH: "high",
  MEDIUM: "moderate",
  LOW: "normal",
} as const;

const STEP_TONE: Record<ResponseStep["status"], string> = {
  DONE: "text-status-normal",
  BLOCKED: "text-status-high",
  SKIPPED: "text-content-disabled",
  PENDING: "text-content-secondary",
};

export default function ResponsePlansPage() {
  const { city } = useSelectedCity();
  const [openOnly, setOpenOnly] = useState(true);

  const plansQuery = useQuery({
    queryKey: ["response-plans", city?.slug, openOnly],
    queryFn: () => responseApi.list(city!.slug, openOnly),
    enabled: Boolean(city),
    refetchInterval: 60_000,
  });

  if (!city) return <LoadingState label="Loading city" rows={4} />;

  const plans = plansQuery.data ?? [];

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Action Center"
        subtitle={<>{city.name} · what is being done about each situation, and who has it</>}
        actions={
          <>
            <Button
              variant={openOnly ? "primary" : "secondary"}
              size="sm"
              onClick={() => setOpenOnly(true)}
            >
              Open
            </Button>
            <Button
              variant={openOnly ? "secondary" : "primary"}
              size="sm"
              onClick={() => setOpenOnly(false)}
            >
              All
            </Button>
          </>
        }
      />

      {plansQuery.isError ? (
        <ErrorState
          title="Could not load response plans"
          message={plansQuery.error instanceof Error ? plansQuery.error.message : "Unavailable."}
          onRetry={() => void plansQuery.refetch()}
        />
      ) : plansQuery.isLoading ? (
        <LoadingState label="Loading plans" rows={4} />
      ) : plans.length === 0 ? (
        <EmptyState
          title={openOnly ? "Nothing open" : "No response plans"}
          description={
            openOnly
              ? "No plan is in draft or active. Closed ones are under All."
              : "Nothing has been planned yet. Plans are written by people — the platform contributes at most the recommended action a rule already computed."
          }
        />
      ) : (
        <div className="space-y-4">
          {plans.map((plan) => (
            <PlanCard key={plan.id} plan={plan} />
          ))}
        </div>
      )}
    </div>
  );
}

function PlanCard({ plan }: { plan: ResponsePlan }) {
  const queryClient = useQueryClient();
  const [blocking, setBlocking] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["response-plans"] });

  const step = useMutation({
    mutationFn: (vars: { stepId: string; status: string; note?: string }) =>
      responseApi.updateStep(plan.id, vars.stepId, {
        status: vars.status,
        note: vars.note,
      }),
    onSuccess: () => {
      setBlocking(null);
      setReason("");
      void invalidate();
    },
  });

  const planStatus = useMutation({
    mutationFn: (status: string) => responseApi.updatePlan(plan.id, { status }),
    onSuccess: () => void invalidate(),
  });

  const progress = plan.stepsTotal === 0 ? 0 : (plan.stepsDone / plan.stepsTotal) * 100;

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title={plan.title}
        description={
          [
            plan.zoneName,
            plan.alertTitle ? `responding to “${plan.alertTitle}”` : null,
            `by ${plan.createdBy}`,
          ]
            .filter(Boolean)
            .join(" · ")
        }
        action={
          <div className="flex items-center gap-1.5">
            <Badge level={PRIORITY_STATUS[plan.priority]}>{plan.priority.toLowerCase()}</Badge>
            <Badge level={plan.status === "ACTIVE" ? "info" : "neutral"}>
              {plan.status.toLowerCase()}
            </Badge>
          </div>
        }
      />

      {/* Progress per step. One status badge cannot say "three of five, with the
          fourth blocked", so the counts do. */}
      <div className="border-b border-line-subtle px-5 py-3">
        <div className="flex items-center gap-3">
          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-hover">
            <div
              className="h-full rounded-full bg-status-normal transition-[width]"
              style={{ width: `${progress}%` }}
            />
          </div>
          <span className="tabular text-[11px] text-content-secondary">
            {plan.stepsDone} of {plan.stepsTotal}
          </span>
          {plan.stepsBlocked > 0 && (
            <span className="text-[11px] text-status-high">{plan.stepsBlocked} blocked</span>
          )}
        </div>
      </div>

      <ol className="divide-y divide-line-subtle">
        {plan.steps.map((s) => (
          <li key={s.id} className="px-5 py-3">
            <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
              <div className="min-w-0 flex-1">
                <p className={cn("text-[13px] leading-relaxed", STEP_TONE[s.status])}>
                  {s.instruction}
                </p>

                <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
                  {s.fromAlertRule && (
                    // Marked, always. A rule's recommendation and a person's
                    // instruction must never read as the same kind of thing.
                    <span className="rounded border border-line-default px-1.5 py-px text-[10px] uppercase tracking-wide text-content-tertiary">
                      From alert rule
                    </span>
                  )}
                  {s.completedBy && (
                    <span className="text-[10px] text-content-tertiary">
                      done by {s.completedBy}
                    </span>
                  )}
                  {s.note && (
                    <span className="text-[10px] text-status-high">{s.note}</span>
                  )}
                </div>
              </div>

              {plan.status !== "CANCELLED" && (
                <div className="flex shrink-0 gap-1.5">
                  {s.status !== "DONE" && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => step.mutate({ stepId: s.id, status: "DONE" })}
                    >
                      Done
                    </Button>
                  )}
                  {s.status === "PENDING" && (
                    <Button variant="secondary" size="sm" onClick={() => setBlocking(s.id)}>
                      Block
                    </Button>
                  )}
                  {s.status !== "PENDING" && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => step.mutate({ stepId: s.id, status: "PENDING" })}
                    >
                      Reopen
                    </Button>
                  )}
                </div>
              )}
            </div>

            {blocking === s.id && (
              // A reason is required by the API, and asking for it here beats
              // surfacing a 400. A stalled step with no reason is a dead end
              // nobody can pick up later.
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <Input
                  label="Why is it blocked?"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Awaiting police sign-off"
                  className="max-w-[320px]"
                />
                <Button
                  size="sm"
                  disabled={reason.trim().length === 0}
                  onClick={() =>
                    step.mutate({ stepId: s.id, status: "BLOCKED", note: reason.trim() })
                  }
                >
                  Block
                </Button>
                <Button variant="secondary" size="sm" onClick={() => setBlocking(null)}>
                  Cancel
                </Button>
              </div>
            )}
          </li>
        ))}
      </ol>

      <div className="flex flex-wrap gap-2 border-t border-line-subtle px-5 py-3">
        {plan.status === "DRAFT" && (
          <Button size="sm" onClick={() => planStatus.mutate("ACTIVE")}>
            Activate
          </Button>
        )}
        {plan.status === "ACTIVE" && (
          <>
            <Button size="sm" onClick={() => planStatus.mutate("COMPLETED")}>
              Complete
            </Button>
            <Button variant="secondary" size="sm" onClick={() => planStatus.mutate("CANCELLED")}>
              Cancel
            </Button>
          </>
        )}
        {(plan.status === "COMPLETED" || plan.status === "CANCELLED") && (
          <span className="text-[11px] text-content-tertiary">
            Closed {plan.closedAt ? new Date(plan.closedAt).toLocaleString() : ""}
          </span>
        )}
      </div>
    </Card>
  );
}
