'use server'

import { headers } from 'next/headers'

import { registerPreRegistration } from '../../lib/pre-registration'
import type { PreRegistrationState } from '../../lib/pre-registration-state'

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const requests = new Map<string, { count: number; resetAt: number }>()

function isRateLimited(ip: string) {
  const now = Date.now()
  const current = requests.get(ip)
  if (!current || current.resetAt <= now) {
    requests.set(ip, { count: 1, resetAt: now + 60_000 })
    return false
  }
  current.count += 1
  return current.count > 5
}

export async function submitPreRegistration(_: PreRegistrationState, formData: FormData): Promise<PreRegistrationState> {
  const email = String(formData.get('email') ?? '').trim().toLowerCase()
  const consent = formData.get('privacyConsent') === 'on'
  const honeypot = String(formData.get('company') ?? '')
  const requestHeaders = await headers()
  const ip = requestHeaders.get('x-forwarded-for')?.split(',')[0]?.trim() ?? 'unknown'

  if (honeypot || isRateLimited(ip)) return { status: 'success', messageKey: 'success' }
  if (!emailPattern.test(email)) return { status: 'error', messageKey: 'invalidEmail' }
  if (!consent) return { status: 'error', messageKey: 'consentRequired' }

  try {
    await registerPreRegistration(email)
    return { status: 'success', messageKey: 'success' }
  } catch {
    return { status: 'error', messageKey: 'failure' }
  }
}
