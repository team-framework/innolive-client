"use client";

import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import { useLocale } from "@/components/locale-provider";

// Login-only shell. Mirrors AuthShell's structure and DOM/focus order (logo → form →
// policy nav) but adds the redesigned backdrop, card depth and a login-scoped dark
// theme. Kept separate from AuthShell so the signup page stays byte-for-byte unchanged.
export function LoginShell({ children }: { children: ReactNode }) {
  const { href, messages } = useLocale();
  return (
    <main
      id="main"
      data-page="auth"
      className="login-scope relative min-h-dvh overflow-hidden bg-background-primary max-lg:bg-background-secondary dark:bg-[#09090b]"
    >
      {/* Soft layered glow backdrop (scoped, decorative). */}
      <div aria-hidden="true" className="login-glow pointer-events-none absolute inset-0" />

      {/* Auth pages hide the site header, so show the logo where the header puts it. */}
      <div className="absolute inset-x-0 top-0 z-10 lg:pt-[var(--header-offset-desktop)]">
        <div className="flex h-[var(--header-height)] items-end px-5 py-5 lg:p-8 lg:px-12">
          <Link
            href={href("/")}
            aria-label={messages.header.home}
            className="login-logo shrink-0 rounded-sm transition-opacity duration-200 hover:opacity-70 max-lg:-my-[10px] max-lg:py-[10px]"
          >
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={94} height={24} unoptimized className="block lg:hidden" />
            <Image src="/brand/logo-header.svg" alt="InnoLive" width={124} height={32} unoptimized className="hidden lg:block" />
          </Link>
        </div>
      </div>

      <div className="relative z-[1] flex min-h-dvh flex-col items-center justify-center px-[var(--page-gutter)] py-16 max-lg:justify-start max-lg:px-5 max-lg:pb-10 max-lg:pt-[calc(var(--header-height)+2rem)] lg:pb-20 lg:pt-28">
        <div className="login-card login-rise flex w-full max-w-[60rem] flex-col rounded-[28px] max-lg:rounded-none min-[48rem]:flex-row min-[48rem]:bg-background-secondary min-[48rem]:p-3 dark:min-[48rem]:bg-[#141418]">
          <div className="flex w-full flex-col items-center justify-center max-lg:p-0 min-[48rem]:px-8 min-[48rem]:py-12 min-[64rem]:min-h-[33rem] min-[64rem]:w-[27rem] min-[64rem]:shrink-0 min-[64rem]:px-12 min-[64rem]:py-14">
            {children}
          </div>
          <div className="login-art relative hidden flex-1 overflow-clip rounded-[18px] min-[64rem]:block min-[64rem]:min-h-[33rem] min-[64rem]:self-stretch">
            <Image
              src="/account/auth-artwork.jpg"
              alt=""
              fill
              sizes="(min-width: 64rem) 32rem, 0px"
              className="object-cover"
            />
            <div aria-hidden="true" className="login-art-veil absolute inset-0" />
            <Image
              src="/account/logo-wt.svg"
              alt="InnoLive"
              width={215}
              height={56}
              unoptimized
              className="absolute top-1/2 left-1/2 h-12 w-[11.5rem] -translate-x-1/2 -translate-y-1/2"
            />
          </div>
        </div>
        <nav
          className="login-rise mt-8 flex flex-wrap items-center justify-center gap-2 text-base font-medium text-text-primary dark:text-[#a1a1aa] max-lg:mt-4 max-lg:gap-x-4 max-lg:font-normal max-lg:text-text-secondary lg:absolute lg:bottom-8 lg:left-1/2 lg:mt-0 lg:-translate-x-1/2 [animation-delay:240ms]"
          aria-label={messages.auth.policyNav}
        >
          <Link
            href={href("/privacy")}
            className="rounded-sm underline [text-underline-position:from-font] transition-colors hover:text-text-primary dark:hover:text-white max-lg:inline-flex max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:items-center max-lg:justify-center"
          >
            {messages.auth.privacy}
          </Link>
          <Link
            href={href("/terms")}
            className="rounded-sm underline [text-underline-position:from-font] transition-colors hover:text-text-primary dark:hover:text-white max-lg:inline-flex max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:items-center max-lg:justify-center"
          >
            {messages.auth.terms}
          </Link>
        </nav>
      </div>
    </main>
  );
}
