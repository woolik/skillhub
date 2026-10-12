import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { Card } from '@/shared/ui/card'
import { useSessionBootstrap } from '@/features/auth/use-session-bootstrap'

/**
 * Dongbuddy trusted-gateway SSO exchange page.
 *
 * Dongbuddy opens the system browser at `/sso/dongbuddy?ticket=<HMAC ticket>`.
 * The ticket cannot ride on the bootstrap API call directly (the SPA calls it
 * through the shared client), so this page parks it in a 60s SameSite=Lax
 * cookie, lets `authApi.bootstrapSession('dongbuddy')` carry that cookie to
 * `POST /api/v1/auth/session/bootstrap`, then clears the cookie and replaces
 * the history entry so the one-time ticket never lingers in the URL.
 */
const TICKET_COOKIE = 'db_sso'

function setTicketCookie(ticket: string) {
  document.cookie = `${TICKET_COOKIE}=${encodeURIComponent(ticket)}; path=/; max-age=60; SameSite=Lax`
}

function clearTicketCookie() {
  document.cookie = `${TICKET_COOKIE}=; path=/; max-age=0; SameSite=Lax`
}

export function SsoDongbuddyPage() {
  const { ticket } = useSearch({ from: '/sso/dongbuddy' })
  const navigate = useNavigate()
  const bootstrap = useSessionBootstrap()
  const [error, setError] = useState<string | null>(ticket ? null : 'missing')
  const attemptedRef = useRef(false)

  useEffect(() => {
    if (!ticket || attemptedRef.current) {
      return
    }
    attemptedRef.current = true
    setTicketCookie(ticket)
    bootstrap.mutateAsync('dongbuddy', {
      onSuccess: async () => {
        clearTicketCookie()
        // Land on the authenticated default dashboard: Dongbuddy provisioning
        // no longer creates a personal namespace (team spaces are
        // admin-assigned), so /space/<userId> would dead-end for fresh
        // accounts; /dashboard/namespaces is avoided because the upstream
        // me/namespaces endpoint 500s on some deployments while dashboard
        // only needs my-skills + tokens.
        await navigate({ to: '/dashboard', replace: true })
      },
      onError: (err) => {
        clearTicketCookie()
        setError(err instanceof Error ? err.message : String(err))
      },
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ticket])

  return (
    <div className="flex min-h-[60vh] items-center justify-center px-4">
      <Card className="w-full max-w-md p-6 space-y-3">
        <h1 className="text-lg font-semibold">Dongbuddy 免登 / Dongbuddy SSO</h1>
        {error ? (
          <>
            <p className="text-sm text-muted-foreground">
              {error === 'missing'
                ? '缺少免登票据，请从 Dongbuddy「技能市场 → 在浏览器打开」重新进入。'
                : `免登失败 / bootstrap failed: ${error}`}
            </p>
            <Link to="/login" className="text-sm text-primary underline">
              前往登录 / Go to login
            </Link>
          </>
        ) : (
          <p className="text-sm text-muted-foreground">正在建立会话… / Establishing session…</p>
        )}
      </Card>
    </div>
  )
}
