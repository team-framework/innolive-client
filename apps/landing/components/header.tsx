"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AuthNavigation } from "@/components/auth-navigation";
import { DownloadMenu } from "@/components/download-menu";
import { cn } from "@/lib/cn";
import { navLinks } from "@/lib/site";

const navClassName =
  "inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline md:text-2xl";

export function Header() {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  useEffect(() => {
    if (!mobileMenuOpen) return;

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMobileMenuOpen(false);
    };

    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [mobileMenuOpen]);

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
      <div className="relative mx-auto flex w-full max-w-[1640px] items-center justify-between gap-3 bg-background-secondary/90 px-5 py-3 backdrop-blur md:bg-transparent md:p-2.5">
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
        <div className="flex items-center gap-2 md:hidden">
          <DownloadMenu />
          <button
            type="button"
            aria-controls="mobile-navigation"
            aria-expanded={mobileMenuOpen}
            aria-label={mobileMenuOpen ? "메뉴 닫기" : "메뉴 열기"}
            className="inline-flex size-11 items-center justify-center rounded-full text-text-primary transition-colors hover:bg-background-primary"
            onClick={() => setMobileMenuOpen((open) => !open)}
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="size-6" fill="none">
              <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" strokeWidth="1.8" />
            </svg>
          </button>
        </div>
        <nav
          id="mobile-navigation"
          aria-label="주요"
          className={cn(
            "absolute inset-x-0 top-full hidden flex-col gap-4 border-t border-surface-primary bg-background-secondary px-5 py-5 shadow-button md:static md:flex md:w-auto md:flex-row md:items-center md:justify-end md:gap-6 md:border-0 md:bg-transparent md:p-0 md:shadow-none",
            mobileMenuOpen && "flex",
          )}
        >
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={navClassName}
              onClick={() => setMobileMenuOpen(false)}
            >
              {link.label}
            </Link>
          ))}
          <div className="hidden md:block">
            <DownloadMenu />
          </div>
          <AuthNavigation className={navClassName} />
        </nav>
      </div>
    </header>
  );
}
