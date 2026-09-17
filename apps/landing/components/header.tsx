import Image from "next/image";
import Link from "next/link";
import { DownloadMenu } from "@/components/download-menu";
import { navLinks } from "@/lib/site";

export function Header() {
  return (
    <header className="relative z-10 w-full">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-30 focus:bg-background-secondary focus:px-3 focus:py-2 focus:text-text-primary"
      >
        본문으로 건너뛰기
      </a>
      <div className="mx-auto flex w-full max-w-[1640px] items-center justify-between gap-3 px-4 py-2.5 sm:px-8">
        <Link href="/" className="shrink-0" aria-label="InnoLive 홈">
          <Image
            src="/brand/logo-header.svg"
            alt="InnoLive"
            width={124}
            height={32}
            unoptimized
            priority
          />
        </Link>
        <nav
          aria-label="주요"
          className="flex flex-wrap items-center justify-end gap-x-4 gap-y-2 sm:gap-6"
        >
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className="inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline sm:text-2xl"
            >
              {link.label}
            </Link>
          ))}
          <DownloadMenu />
          <Link
            href="/login"
            className="inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline sm:text-2xl"
          >
            Login
          </Link>
        </nav>
      </div>
    </header>
  );
}
