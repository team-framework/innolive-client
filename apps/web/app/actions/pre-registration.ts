'use server'

import { headers } from 'next/headers'

import { registerPreRegistration } from '../../lib/pre-registration'

export type PreRegistrationState = { status: 'idle' | 'success' | 'error'; message?: string }
export const initialState: PreRegistrationState = { status: 'idle' }

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

  if (honeypot || isRateLimited(ip)) return { status: 'success', message: '사전등록이 완료되었습니다. 출시 소식으로 다시 만날게요.' }
  if (!emailPattern.test(email)) return { status: 'error', message: '올바른 이메일 주소를 입력해주세요.' }
  if (!consent) return { status: 'error', message: '개인정보 수집·이용에 동의해주세요.' }

  try {
    await registerPreRegistration(email)
    return { status: 'success', message: '사전등록이 완료되었습니다. 출시 소식으로 다시 만날게요.' }
  } catch {
    return { status: 'error', message: '등록 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.' }
  }
}
