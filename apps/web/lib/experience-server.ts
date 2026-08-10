import type { TFunction } from 'i18next'

export type ExperienceServerEndpoints = {
  signalingURL: URL
  sessionsURL: URL
}

export function getExperienceServerEndpoints(t: TFunction): ExperienceServerEndpoints {
  const configuredURL = process.env.NEXT_PUBLIC_INNOLIVE_SIGNALING_URL?.trim()
  if (!configuredURL) {
    throw new Error(t('experience.errors.serverUrlMissing'))
  }

  const signalingURL = new URL(configuredURL)
  if (signalingURL.protocol === 'http:') {
    signalingURL.protocol = 'ws:'
  } else if (signalingURL.protocol === 'https:') {
    signalingURL.protocol = 'wss:'
  }

  if (signalingURL.protocol !== 'ws:' && signalingURL.protocol !== 'wss:') {
    throw new Error(t('experience.errors.serverUrlProtocol'))
  }

  const sessionsURL = new URL(signalingURL)
  sessionsURL.protocol = signalingURL.protocol === 'wss:' ? 'https:' : 'http:'
  sessionsURL.pathname = '/sessions'
  sessionsURL.search = ''
  sessionsURL.hash = ''

  return { signalingURL, sessionsURL }
}

export async function createExperienceSession(t: TFunction) {
  const { signalingURL, sessionsURL } = getExperienceServerEndpoints(t)
  const sessionResponse = await fetch(sessionsURL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      metadata: {
        title: 'InnoLive Web Experience',
        broadcaster_id: 'web-experience',
        client: 'innolive-web',
      },
    }),
  })

  if (!sessionResponse.ok) {
    throw new Error(t('experience.errors.sessionCreationFailed', { status: sessionResponse.status }))
  }

  const sessionPayload: unknown = await sessionResponse.json()
  if (!sessionPayload || typeof sessionPayload !== 'object' || typeof (sessionPayload as { session_id?: unknown }).session_id !== 'string') {
    throw new Error(t('experience.errors.missingSessionId'))
  }

  return { signalingURL, sessionID: (sessionPayload as { session_id: string }).session_id }
}

export async function deleteExperienceSession(sessionID: string, t: TFunction) {
  try {
    const { sessionsURL } = getExperienceServerEndpoints(t)
    const sessionURL = new URL(`/sessions/${encodeURIComponent(sessionID)}`, sessionsURL)
    await fetch(sessionURL, { method: 'DELETE' })
  } catch {
  }
}
