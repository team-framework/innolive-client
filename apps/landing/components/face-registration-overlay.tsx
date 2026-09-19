"use client";

import Image from "next/image";
import { useLocale } from "@/components/locale-provider";

export function FaceRegistrationOverlay() {
  const { messages } = useLocale();
  return (
    <div className="flex w-[min(calc(100vw-3rem),40.4375rem)] max-w-[40.4375rem] flex-col overflow-clip rounded-[12px] bg-background-secondary min-[48rem]:h-[45rem] min-[48rem]:flex-row">
      <div className="flex w-full flex-col items-start justify-between gap-8 p-8 min-[48rem]:h-full min-[48rem]:w-[15.125rem] min-[48rem]:shrink-0">
        <div className="flex flex-col items-start gap-2.5">
          <p className="text-sm font-medium text-text-secondary">{messages.face.preview}</p>
          <p className="text-[2.25rem] font-medium leading-none text-text-primary">
            {messages.face.title}
          </p>
          <p className="text-2xl font-normal leading-[1.2] text-text-secondary">
            {messages.face.subtitle}
          </p>
        </div>
        <p className="text-center text-xs leading-[1.3] text-text-secondary">
          {messages.face.termsNote}
        </p>
      </div>
      <div
        className="hidden w-px self-stretch bg-text-secondary min-[48rem]:block"
        aria-hidden="true"
      />
      <div className="relative aspect-[405/720] w-full min-[48rem]:h-[45rem] min-[48rem]:w-[25.3125rem] min-[48rem]:shrink-0 min-[48rem]:aspect-auto">
        <Image
          src="/try-out/face-preview.jpg"
          alt=""
          width={641}
          height={962}
          className="absolute inset-0 size-full object-cover"
        />
        <Image
          src="/try-out/face-guide.svg"
          alt=""
          width={405}
          height={720}
          unoptimized
          className="absolute inset-0 size-full"
        />
        <div className="absolute inset-x-[13.086%] bottom-0 flex flex-col items-center gap-1 pb-4 text-center min-[48rem]:top-[87.083%] min-[48rem]:bottom-auto min-[48rem]:w-[73.827%] min-[48rem]:gap-2 min-[48rem]:pb-0">
          <p className="w-full text-base font-semibold leading-[1.2] text-text-reversed min-[48rem]:text-xl min-[48rem]:leading-none">
            {messages.face.registered}
          </p>
          <p className="w-full text-sm font-medium leading-[1.3] text-[#fbfbfb] min-[48rem]:text-base">
            {messages.face.guide}
          </p>
        </div>
      </div>
    </div>
  );
}
