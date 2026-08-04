"use client";

import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import {
  clearSession,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
  setSessionExpiredHandler,
} from "@/lib/api/client";
import { authApi } from "@/lib/api/endpoints";
import type { UserProfile } from "@/lib/api/types";

interface SessionValue {
  user: UserProfile | null;
  /** True until the initial session restore finishes; guards render a skeleton. */
  isLoading: boolean;
  signIn: (email: string, password: string) => Promise<UserProfile>;
  signOut: () => Promise<void>;
  refreshProfile: () => Promise<void>;
  /**
   * Whether the signed-in user holds a permission.
   *
   * This governs presentation only — hiding a control the user cannot use. The
   * API enforces the same permission independently, so a user who forges this
   * check client-side gains nothing (docs/SECURITY.md §3).
   */
  can: (permission: string) => boolean;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<UserProfile | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Restore the session on mount. The access token lives in memory and is gone
  // after a reload, so it is rebuilt from the refresh token when one exists.
  useEffect(() => {
    let cancelled = false;

    async function restore() {
      if (!getRefreshToken()) {
        if (!cancelled) setIsLoading(false);
        return;
      }
      try {
        // The client refreshes transparently on the 401 this triggers.
        const profile = await authApi.me();
        if (!cancelled) setUser(profile);
      } catch {
        clearSession();
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    void restore();
    return () => {
      cancelled = true;
    };
  }, []);

  // When a refresh fails for good, drop the user at sign-in rather than leaving
  // them on a page whose requests all silently fail.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      router.push("/login?reason=expired");
    });
    return () => setSessionExpiredHandler(null);
  }, [router]);

  const signIn = useCallback(async (email: string, password: string) => {
    const tokens = await authApi.login({ email, password });
    setAccessToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setUser(tokens.user);
    return tokens.user;
  }, []);

  const signOut = useCallback(async () => {
    const refreshToken = getRefreshToken();
    try {
      // Revokes the session server-side. A failure here must not strand the user
      // in a signed-in UI, so local state is cleared either way.
      if (refreshToken) await authApi.logout(refreshToken);
    } catch {
      // Intentionally ignored; local sign-out proceeds.
    } finally {
      clearSession();
      setUser(null);
      router.push("/login");
    }
  }, [router]);

  const refreshProfile = useCallback(async () => {
    setUser(await authApi.me());
  }, []);

  const can = useCallback(
    (permission: string) => user?.permissions.includes(permission) ?? false,
    [user],
  );

  const value = useMemo<SessionValue>(
    () => ({ user, isLoading, signIn, signOut, refreshProfile, can }),
    [user, isLoading, signIn, signOut, refreshProfile, can],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession must be used inside a SessionProvider");
  }
  return context;
}
