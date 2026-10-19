"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { AuthNavigation } from "@/components/auth-navigation";
import { DownloadMenu } from "@/components/download-menu";
import { LanguageSwitch } from "@/components/language-switch";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";
import { navLinks } from "@/lib/site";

// Below lg the links are stacked card rows (chevron, pressed state, current page inverted);
// lg and up keep the inline nav.
const navClassName =
  "inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline max-lg:min-h-[56px] max-lg:w-full max-lg:justify-between max-lg:gap-3 max-lg:rounded-[14px] max-lg:bg-background-secondary max-lg:px-5 max-lg:text-[18px] max-lg:font-semibold max-lg:shadow-[0_0_0_1px_#0000000f,0_1px_3px_#0000000d] max-lg:transition-[background-color,transform] max-lg:duration-100 max-lg:hover:no-underline max-lg:after:size-5 max-lg:after:shrink-0 max-lg:after:-rotate-90 max-lg:after:bg-[url(/icons/chevron-down.svg)] max-lg:after:bg-contain max-lg:after:bg-center max-lg:after:bg-no-repeat max-lg:after:content-[''] max-lg:active:scale-[0.98] max-lg:active:bg-button-primary-hover max-lg:aria-[current=page]:bg-text-primary max-lg:aria-[current=page]:text-text-reversed max-lg:aria-[current=page]:after:invert lg:text-2xl";

export function Header() {
  const { href, messages } = useLocale();
  const pathname = usePathname();
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
      className="fixed inset-x-0 top-0 z-50 w-full lg:pt-[var(--header-offset-desktop)]"
    >
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-30 focus:bg-background-secondary focus:px-3 focus:py-2 focus:text-text-primary"
      >
        {messages.common.skipToMain}
      </a>
      <div className=" absolute mx-auto flex w-screen h-[var(--header-height)] top-0 items-end justify-between gap-3  px-5 py-5 backdrop-blur bg-background-primary/80 lg:p-8 lg:px-12">
        <Link href={href("/")} className="shrink-0 max-lg:-my-[10px] max-lg:py-[10px]" aria-label={messages.header.home}>
          <Image
            src="/brand/logo-header.svg"
            alt="InnoLive"
            width={124}
            height={32}
            unoptimized
            priority
            className="hidden lg:block"
          />
          <Image
            src="/brand/logo-header.svg"
            alt="InnoLive"
            width={94}
            height={24}
            unoptimized
            priority
            className="block lg:hidden"
          />
        </Link>
        <div className="flex items-center gap-2 lg:hidden">
          <DownloadMenu />
          <button
            type="button"
            aria-controls={navigationId}
            aria-expanded={mobileMenuOpen}
            aria-label={mobileMenuOpen ? messages.header.closeMenu : messages.header.openMenu}
            className="inline-flex size-11 items-center max-lg:-my-[6px] max-lg:size-[44px] justify-center rounded-full text-text-primary transition-colors hover:bg-background-primary"
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
            "absolute inset-x-0 top-full flex-col gap-1 border-t border-surface-primary bg-background-secondary px-5 py-5 shadow-button max-lg:gap-2.5 max-lg:rounded-b-[24px] max-lg:bg-background-primary max-lg:px-4 max-lg:pb-5 max-lg:pt-4 max-lg:shadow-[0_16px_32px_#0000001a] lg:static lg:flex lg:w-auto lg:flex-row lg:items-center lg:justify-end lg:gap-6 lg:border-0 lg:bg-transparent lg:p-0 lg:shadow-none",
            mobileMenuOpen ? "flex" : "hidden",
          )}
        >
          {navLinks.map((link) => (
            <Link
              key={link.href}
              href={href(link.href)}
              aria-current={pathname === href(link.href) ? "page" : undefined}
              className={navClassName}
              onClick={() => setMobileMenuOpen(false)}
            >
              {messages.nav[link.id]}
            </Link>
          ))}
          <div className="hidden lg:block">
            <DownloadMenu />
          </div>
          <AuthNavigation className={navClassName} />
          <LanguageSwitch className="pt-2 max-lg:self-center max-lg:pt-3 lg:pt-0" />
        </nav>
      </div>
    </header>
  );
}
