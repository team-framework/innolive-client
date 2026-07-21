'use client'

import Link from 'next/link'
import { useActionState } from 'react'

import { initialState, submitPreRegistration } from '../app/actions/pre-registration'

export function PreRegistrationForm() {
  const [state, formAction, pending] = useActionState(submitPreRegistration, initialState)

  return (
    <form action={formAction} className="w-full text-left sm:w-[60vw] lg:w-[36.67vw]">
      <div className="flex gap-2 rounded-[114px] bg-white/90 p-1 md:gap-[clamp(12px,0.83vw,16px)]">
        <label className="min-w-0 flex-1 px-3 py-2.5 md:px-[clamp(9px,0.63vw,12px)] md:py-[clamp(7.5px,0.52vw,10px)]">
          <span className="sr-only">이메일</span>
          <input required name="email" type="email" autoComplete="email" placeholder="이메일을 입력하세요." className="w-full bg-transparent text-base text-black outline-none placeholder:text-[#656565] md:text-[clamp(15px,1.04vw,20px)]" />
        </label>
        <button disabled={pending} type="submit" className="shrink-0 rounded-[48px] bg-white px-4 py-3 text-base text-black disabled:cursor-wait disabled:opacity-60 md:px-[clamp(18px,1.25vw,24px)] md:py-[clamp(9px,0.63vw,12px)] md:text-[clamp(15px,1.04vw,20px)]">{pending ? '등록 중' : '등록하기'}</button>
      </div>
      <label className="mt-[clamp(9px,0.63vw,12px)] flex cursor-pointer items-center gap-1 px-3 text-sm text-[#b0b0b0] md:px-[clamp(9px,0.63vw,12px)] md:text-[clamp(12px,0.83vw,16px)]">
        <input required name="privacyConsent" type="checkbox" className="size-3.5 accent-white" />
        <span>개인정보 수집·이용에 동의합니다.</span><Link href="/privacy" className="underline">자세히 보기</Link>
      </label>
      <input name="company" tabIndex={-1} autoComplete="off" aria-hidden="true" className="hidden" />
      {state.status !== 'idle' && <p aria-live="polite" className={`mt-3 px-3 text-sm ${state.status === 'error' ? 'text-red-300' : 'text-emerald-300'}`}>{state.message}</p>}
    </form>
  )
}
