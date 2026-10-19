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
      className="relative min-h-dvh bg-background-primary max-md:bg-background-secondary"
    >
      {/* Auth pages hide the site header, and below lg the artwork panel and its logo are hidden too,
          so show the logo where the header puts it. */}
      <div className="absolute inset-x-0 top-0 z-10 md:pt-[var(--header-offset-desktop)] lg:hidden">
        <div className="flex h-[var(--header-height)] items-end px-5 py-5 md:p-8 md:px-12">
          <Link
            href={href("/")}
            aria-label={messages.header.home}
            className="shrink-0 max-md:-my-[10px] max-md:py-[10px] md:-my-[6px] md:py-[6px]"
          >
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={94} height={24} unoptimized className="block md:hidden" />
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={124} height={32} unoptimized className="hidden md:block" />
          </Link>
        </div>
      </div>
      <div className="flex min-h-dvh flex-col items-center justify-center px-[var(--page-gutter)] py-16 pb-24 max-md:justify-start max-md:px-5 max-md:pb-10 max-md:pt-[calc(var(--header-height)+2rem)] md:max-lg:pt-[calc(var(--header-height)+var(--header-offset-desktop)+2rem)] lg:min-h-[max(100dvh,62rem)] lg:py-0">
        <div className="flex w-full max-w-[86rem] flex-col overflow-clip rounded-[24px] bg-background-secondary shadow-[0_0_32px_0_#0000000d] max-md:overflow-visible max-md:rounded-none max-md:shadow-none min-[48rem]:flex-row">
          <div className="flex w-full flex-col items-center justify-center gap-7 px-5 pb-8 pt-[4.5rem] max-md:items-stretch max-md:p-0 sm:px-8 min-[64rem]:max-w-[36rem]">
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
          className="mt-8 flex flex-wrap items-center justify-center gap-2 max-lg:gap-x-4 max-md:mt-4 max-md:font-normal max-md:text-text-secondary text-base font-medium text-text-primary lg:absolute lg:bottom-8 lg:left-1/2 lg:mt-0 lg:-translate-x-1/2"
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
