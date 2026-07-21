'use client'

import Link from 'next/link'
import { useActionState, useState, type FormEvent } from 'react'

import { submitPreRegistration } from '../app/actions/pre-registration'
import { initialPreRegistrationState } from '../lib/pre-registration-state'

export function PreRegistrationForm() {
  const [state, formAction, pending] = useActionState(submitPreRegistration, initialPreRegistrationState)
  const [validationError, setValidationError] = useState<'email' | 'consent' | null>(null)

  function validateBeforeSubmit(event: FormEvent<HTMLFormElement>) {
    const formData = new FormData(event.currentTarget)
    const email = String(formData.get('email') ?? '').trim()
    const consent = formData.get('privacyConsent') === 'on'

    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      event.preventDefault()
      setValidationError('email')
      return
    }

    if (!consent) {
      event.preventDefault()
      setValidationError('consent')
      return
    }

    setValidationError(null)
  }

  return (
    <form action={formAction} noValidate onSubmit={validateBeforeSubmit} className="w-full text-left sm:w-[60vw] lg:w-[36.67vw]">
      <div className={`flex items-center gap-2 rounded-[114px] p-1 transition-colors md:gap-[clamp(12px,0.83vw,16px)] ${validationError === 'email' ? 'bg-red-50 ring-2 ring-inset ring-red-500' : 'bg-white/90'}`}>
        <label className="flex min-w-0 flex-1 items-center px-4 md:px-[clamp(12px,0.83vw,16px)]">
          <span className="sr-only">이메일</span>
          <input name="email" type="email" autoComplete="email" aria-invalid={validationError === 'email'} aria-describedby={validationError === 'email' ? 'email-error' : undefined} onInput={() => validationError === 'email' && setValidationError(null)} placeholder="이메일을 입력하세요." className="w-full py-2.5 text-left text-base leading-none text-black outline-none placeholder:text-[#656565] md:py-[clamp(7.5px,0.52vw,10px)] md:text-[clamp(15px,1.04vw,20px)]" />
        </label>
        <button disabled={pending} type="submit" className="shrink-0 cursor-pointer rounded-[48px] bg-white px-4 py-3 text-base text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] disabled:cursor-wait disabled:opacity-60 md:px-[clamp(18px,1.25vw,24px)] md:py-[clamp(9px,0.63vw,12px)] md:text-[clamp(15px,1.04vw,20px)]">{pending ? '등록 중' : '등록하기'}</button>
      </div>
      {validationError === 'email' && <p id="email-error" role="alert" className="mt-2 px-3 text-sm text-red-300">이메일 주소를 입력해주세요.</p>}
      <label className={`mt-[clamp(9px,0.63vw,12px)] flex cursor-pointer items-center gap-1 px-3 text-sm md:px-[clamp(9px,0.63vw,12px)] md:text-[clamp(12px,0.83vw,16px)] ${validationError === 'consent' ? 'text-red-300' : 'text-[#b0b0b0]'}`}>
        <input name="privacyConsent" type="checkbox" aria-invalid={validationError === 'consent'} onChange={() => validationError === 'consent' && setValidationError(null)} className="size-3.5 cursor-pointer accent-white" />
        <span>개인정보 수집·이용에 동의합니다.</span><Link href="/privacy" className="cursor-pointer underline transition-colors hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">자세히 보기</Link>
      </label>
      {validationError === 'consent' && <p role="alert" className="mt-2 px-3 text-sm text-red-300">개인정보 수집·이용 동의가 필요합니다.</p>}
      <input name="company" tabIndex={-1} autoComplete="off" aria-hidden="true" className="hidden" />
      {state.status !== 'idle' && <p aria-live="polite" className={`mt-3 px-3 text-sm ${state.status === 'error' ? 'text-red-300' : 'text-emerald-300'}`}>{state.message}</p>}
    </form>
  )
}
