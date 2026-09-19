import Image from "next/image";
import Link from "next/link";
import { DownloadMenu } from "@/components/download-menu";
import { navLinks } from "@/lib/site";

const navClassName =
  "inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline md:text-2xl";

export function Header() {
  return (
    <header
      data-fixed-header
      className="fixed inset-x-0 top-0 z-50 w-full md:pt-[var(--header-offset-desktop)]"
    >
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-30 focus:bg-background-secondary focus:px-3 focus:py-2 focus:text-text-primary"
      >
        본문으로 건너뛰기
      </a>
      <div className="mx-auto flex w-full max-w-[1640px] flex-col gap-2 p-2.5 md:flex-row md:items-center md:justify-between md:gap-3">
        <Link href="/" className="shrink-0 self-start" aria-label="InnoLive 홈">
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
          className="flex w-full flex-nowrap items-center justify-between gap-3 md:w-auto md:justify-end md:gap-6"
        >
          {navLinks.map((link) => (
            <Link key={link.href} href={link.href} className={navClassName}>
              {link.label}
            </Link>
          ))}
          <DownloadMenu />
          <Link href="/login" className={navClassName}>
            Login
          </Link>
        </nav>
      </div>
    </header>
  );
}
