"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { AuthNavigation } from "@/components/auth-navigation";
import { DownloadMenu } from "@/components/download-menu";
import { LanguageSwitch } from "@/components/language-switch";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";
import { navLinks } from "@/lib/site";

// Below md the links are stacked, button-like rows with a pressed state; md and up keep the inline nav.
const navClassName =
  "inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline max-md:min-h-[52px] max-md:rounded-[12px] max-md:border max-md:border-surface-primary max-md:bg-background-primary max-md:text-[18px] max-md:font-semibold max-md:transition-[background-color,transform] max-md:duration-100 max-md:hover:no-underline max-md:active:scale-[0.98] max-md:active:bg-button-primary-pressed md:text-2xl md:max-lg:-my-[7.5px] md:max-lg:min-h-[44px]";

export function Header() {
  const { href, messages } = useLocale();
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const headerRef = useRef<HTMLElement>(null);
  // The home page renders its own Header beside the hidden layout one, so a fixed id would repeat.
  const navigationId = useId();

  useEffect(() => {
    if (!mobileMenuOpen) return;

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMobileMenuOpen(false);
    };

    // Touch users dismiss the menu by tapping outside it, as with the Download menu.
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!headerRef.current?.contains(event.target as Node)) setMobileMenuOpen(false);
    };

    document.addEventListener("keydown", closeOnEscape);
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () => {
      document.removeEventListener("keydown", closeOnEscape);
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
    };
  }, [mobileMenuOpen]);

  return (
    <header
      ref={headerRef}
      data-fixed-header
      className="fixed inset-x-0 top-0 z-50 w-full md:pt-[var(--header-offset-desktop)]"
    >
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-30 focus:bg-background-secondary focus:px-3 focus:py-2 focus:text-text-primary"
      >
        {messages.common.skipToMain}
      </a>
      <div className=" absolute mx-auto flex w-screen h-[var(--header-height)] top-0 items-end justify-between gap-3  px-5 py-5 backdrop-blur bg-background-primary/80 md:p-8 md:px-12">
        <Link href={href("/")} className="shrink-0 max-md:-my-[10px] max-md:py-[10px] md:max-lg:-my-[6px] md:max-lg:py-[6px]" aria-label={messages.header.home}>
          <Image
            src="/brand/logo-header.svg"
            alt="InnoLive"
            width={124}
            height={32}
            unoptimized
            priority
            className="hidden md:block"
          />
          <Image
            src="/brand/logo-header.svg"
            alt="InnoLive"
            width={94}
            height={24}
            unoptimized
            priority
            className="block md:hidden"
          />
        </Link>
        <div className="flex items-center gap-2 md:hidden">
          <DownloadMenu />
          <button
            type="button"
            aria-controls={navigationId}
            aria-expanded={mobileMenuOpen}
            aria-label={mobileMenuOpen ? messages.header.closeMenu : messages.header.openMenu}
            className="inline-flex size-11 items-center max-md:-my-[6px] max-md:size-[44px] justify-center rounded-full text-text-primary transition-colors hover:bg-background-primary"
            onClick={() => setMobileMenuOpen((open) => !open)}
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" className="size-6" fill="none">
              <path
                d={mobileMenuOpen ? "M6 6l12 12M18 6 6 18" : "M4 7h16M4 12h16M4 17h16"}
                stroke="currentColor"
                strokeWidth="1.8"
              />
            </svg>
          </button>
        </div>
        <nav
          id={navigationId}
          aria-label={messages.header.primaryNav}
          className={cn(
            "absolute inset-x-0 top-full flex-col gap-1 max-md:gap-2 border-t border-surface-primary bg-background-secondary px-5 py-5 shadow-button md:static md:flex md:w-auto md:flex-row md:items-center md:justify-end md:gap-6 md:border-0 md:bg-transparent md:p-0 md:shadow-none",
            mobileMenuOpen ? "flex" : "hidden",
          )}
        >
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={href(link.href)}
              className={navClassName}
              onClick={() => setMobileMenuOpen(false)}
            >
              {messages.nav[link.id]}
            </Link>
          ))}
          <div className="hidden md:block">
            <DownloadMenu />
          </div>
          <AuthNavigation className={navClassName} />
          <LanguageSwitch className="pt-2 max-md:self-center max-md:pt-3 md:pt-0" />
        </nav>
      </div>
    </header>
  );
}
