"use client";

import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import { useLocale } from "@/components/locale-provider";

export function AuthShell({ children }: { children: ReactNode }) {
  const { href, messages } = useLocale();
  return (
    <main
      id="main"
      data-page="auth"
      className="relative min-h-dvh bg-background-primary max-lg:bg-background-secondary"
    >
      {/* Auth pages hide the site header, so show the logo where the header puts it. */}
      <div className="absolute inset-x-0 top-0 z-10 lg:pt-[var(--header-offset-desktop)]">
        <div className="flex h-[var(--header-height)] items-end px-5 py-5 lg:p-8 lg:px-12">
          <Link
            href={href("/")}
            aria-label={messages.header.home}
            className="shrink-0 max-lg:-my-[10px] max-lg:py-[10px]"
          >
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={94} height={24} unoptimized className="block lg:hidden" />
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={124} height={32} unoptimized className="hidden lg:block" />
          </Link>
        </div>
      </div>
      <div className="flex min-h-dvh flex-col items-center justify-center px-[var(--page-gutter)] py-16 pb-24 max-lg:justify-start max-lg:px-5 max-lg:pb-10 max-lg:pt-[calc(var(--header-height)+2rem)] lg:min-h-[max(100dvh,62rem)] lg:pb-20 lg:pt-[8.5rem]">
        <div className="flex w-full max-w-[86rem] flex-col overflow-clip rounded-[24px] bg-background-secondary shadow-[0_0_32px_0_#0000000d] max-lg:overflow-visible max-lg:rounded-none max-lg:shadow-none min-[48rem]:flex-row">
          <div className="flex w-full flex-col items-center justify-center gap-7 px-5 pb-8 pt-[4.5rem] max-lg:p-0 sm:px-8 min-[64rem]:max-w-[36rem]">
            {children}
          </div>
          <div className="relative hidden min-h-[28rem] flex-1 overflow-clip min-[64rem]:block min-[64rem]:h-[50rem] min-[64rem]:max-w-[50rem]">
            <Image
              src="/account/auth-artwork.jpg"
              alt=""
              fill
              sizes="(min-width: 48rem) 50vw, 100vw"
              className="object-cover"
            />
            <Image
              src="/account/logo-wt.svg"
              alt="InnoLive"
              width={215}
              height={56}
              unoptimized
              className="absolute top-1/2 left-1/2 h-14 w-[13.465rem] -translate-x-1/2 -translate-y-1/2"
            />
          </div>
        </div>
        <nav
          className="mt-8 flex flex-wrap items-center justify-center gap-2 max-lg:gap-x-4 max-lg:mt-4 max-lg:font-normal max-lg:text-text-secondary text-base font-medium text-text-primary lg:absolute lg:bottom-8 lg:left-1/2 lg:mt-0 lg:-translate-x-1/2"
          aria-label={messages.auth.policyNav}
        >
          <Link
            href={href("/privacy")}
            className="underline [text-underline-position:from-font] max-lg:inline-flex max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:items-center max-lg:justify-center"
          >
            {messages.auth.privacy}
          </Link>
          <Link
            href={href("/terms")}
            className="underline [text-underline-position:from-font] max-lg:inline-flex max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:items-center max-lg:justify-center"
          >
            {messages.auth.terms}
          </Link>
        </nav>
      </div>
    </main>
  );
}
