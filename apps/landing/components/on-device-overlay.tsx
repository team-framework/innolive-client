"use client";

import Link from "next/link";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";

export function OnDeviceOverlay() {
  const { href, messages } = useLocale();
  return (
    <div className="flex w-[min(calc(100vw-3rem),58.625rem)] max-w-[58.625rem] flex-col overflow-clip rounded-[12px] bg-background-secondary min-[48rem]:h-[25rem] min-[48rem]:flex-row">
      <div className="flex w-full flex-col items-start justify-between gap-8 p-8 min-[48rem]:h-full min-[48rem]:w-[16.5625rem] min-[48rem]:shrink-0">
        <div className="flex flex-col items-start gap-2.5">
          <p className="text-[2.25rem] font-medium leading-none text-text-primary">
            On Device
          </p>
          <p className="break-keep text-2xl font-normal leading-[1.2] text-text-secondary">
            {messages.onDevice.subtitle}
          </p>
        </div>
        <div className="flex w-full flex-col items-center gap-1">
          <Button
            variant="secondary"
            href={href("/signup")}
            showChevron={false}
            className="w-full max-w-none"
          >
            {messages.onDevice.pay}
          </Button>
          <p className="text-center text-xs leading-[1.3] text-text-secondary">
            {messages.onDevice.termsNote}{" "}
            <Link
              href={href("/terms")}
              className="underline [text-underline-position:from-font]"
            >
              {messages.onDevice.termsLink}
            </Link>
          </p>
        </div>
      </div>
      <div
        className="hidden w-px self-stretch bg-text-secondary min-[48rem]:block"
        aria-hidden="true"
      />
      <div className="flex min-h-[12.5rem] w-full flex-1 items-start p-8 min-[48rem]:h-full min-[48rem]:min-h-[25rem]">
        <p className="text-body-lg font-normal leading-[1.25] text-text-primary">
          {messages.onDevice.body}
        </p>
      </div>
    </div>
  );
}
