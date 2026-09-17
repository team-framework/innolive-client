import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";

export function AuthShell({ children }: { children: ReactNode }) {
  return (
    <main
      id="main"
      data-page="auth"
      className="relative min-h-dvh bg-background-primary"
    >
      <div className="flex min-h-dvh flex-col items-center justify-center px-[var(--page-gutter)] py-16 pb-24 lg:min-h-[max(100dvh,62rem)] lg:py-0">
        <div className="flex w-full max-w-[86rem] flex-col overflow-clip rounded-[24px] bg-background-secondary shadow-[0_0_32px_0_#0000000d] min-[48rem]:flex-row">
          <div className="flex w-full flex-col items-center justify-center gap-7 px-5 pb-8 pt-[4.5rem] sm:px-8 min-[48rem]:max-w-[36rem]">
            {children}
          </div>
          <div className="relative hidden min-h-[28rem] flex-1 overflow-clip min-[48rem]:block min-[64rem]:h-[50rem] min-[64rem]:max-w-[50rem]">
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
          className="mt-8 flex flex-wrap items-center justify-center gap-2 text-base font-medium text-text-primary lg:absolute lg:bottom-8 lg:left-1/2 lg:mt-0 lg:-translate-x-1/2"
          aria-label="약관"
        >
          <Link
            href="/privacy"
            className="underline [text-underline-position:from-font]"
          >
            개인정보 처리방침
          </Link>
          <Link
            href="/terms"
            className="underline [text-underline-position:from-font]"
          >
            이용약관
          </Link>
        </nav>
      </div>
    </main>
  );
}
