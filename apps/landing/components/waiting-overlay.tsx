"use client";

import { useLocale } from "@/components/locale-provider";

export function WaitingOverlay() {
  const { messages } = useLocale();
  return (
    <div className="flex w-full max-w-[22.9375rem] flex-col items-center gap-3 rounded-[12px] bg-[#f5f5f5] px-6 py-6 text-center">
      <p className="text-sm font-medium text-text-secondary">{messages.waiting.preview}</p>
      <div className="flex flex-col items-center gap-0.5 text-text-primary">
        <p className="text-2xl font-semibold leading-none">{messages.waiting.queue}</p>
        <p className="text-[3.25rem] font-bold leading-none">{messages.waiting.count}</p>
      </div>
      <p className="break-keep text-lg font-medium leading-[1.3] text-text-secondary">
        {messages.waiting.eta}
      </p>
      <p className="break-keep text-base font-medium leading-[1.3] text-text-secondary">
        {messages.waiting.note}
      </p>
    </div>
  );
}
