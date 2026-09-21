"use client";

import Image from "next/image";
import Link from "next/link";
import { useLocale } from "@/components/locale-provider";
import { interpolate } from "@/lib/locales";
import { socialLinks } from "@/lib/site";

export function Footer() {
  const { href, messages } = useLocale();

  return (
    <footer className="w-full bg-background-footer">
      <div className="mx-auto flex w-full max-w-[1408px] flex-col gap-12 px-4 pt-16 pb-7 sm:gap-[110px] sm:px-8">
        <div className="flex w-full flex-col gap-5">
          <div className="flex max-w-[222px] flex-col gap-1.5">
            <Image
              src="/brand/logo-header.svg"
              alt="InnoLive"
              width={150}
              height={38}
              unoptimized
              className="hidden lg:block"
            />
            <Image
              src="/brand/logo-header.svg"
              alt="InnoLive"
              width={100}
              height={25}
              unoptimized
              className="block lg:hidden"
            />
            <p className="text-lg leading-[1.15] text-text-primary">
              {messages.footer.tagline}
            </p>
          </div>
          <div className="flex flex-wrap items-end justify-between gap-x-8 gap-y-6">
            <div className="flex min-w-0 max-w-[1100px] flex-1 flex-col gap-1.5 text-base leading-[1.3] tracking-[-0.02em] text-text-secondary">
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2">
                <p>{messages.footer.ceo}</p>
                <p>{messages.footer.dpo}</p>
                <a className="underline" href="mailto:contact@innolive.studio">
                  contact@innolive.studio
                </a>
                <p>Hosted by Framework</p>
              </div>
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2">
                <p>{messages.footer.phone}</p>
                <p>{messages.footer.address}</p>
                <p>{messages.footer.copyright}</p>
              </div>
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2 text-text-secondary">
                <Link href={href("/privacy")} className="font-semibold underline">
                  {messages.footer.privacy}
                </Link>
                <Link href={href("/terms")} className="underline">
                  {messages.footer.terms}
                </Link>
                <Link href={href("/support")} className="underline">
                  {messages.footer.support}
                </Link>
                <Link href={href("/#faq")} scroll={false} className="underline">
                  {messages.footer.faq}
                </Link>
              </div>
            </div>
            <ul className="flex flex-wrap items-start gap-2.5">
              {socialLinks.map((social) => {
                const icon = (
                  <Image
                    src={social.src}
                    alt=""
                    width={32}
                    height={32}
                    aria-hidden="true"
                  />
                );

                if (social.href) {
                  return (
                    <li key={social.name}>
                      <a
                        href={social.href}
                        target="_blank"
                        rel="noreferrer"
                        aria-label={social.name}
                        className="block size-8"
                      >
                        {icon}
                      </a>
                    </li>
                  );
                }

                return (
                  <li key={social.name}>
                    <span
                      aria-label={interpolate(messages.footer.socialMissing, {
                        name: social.name,
                      })}
                      className="block size-8"
                    >
                      {icon}
                    </span>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>
        <div className="w-full max-w-[1469px]">
          <Image
            src="/brand/de-identification-wordmark.svg"
            alt="De-Identification"
            width={1469}
            height={126}
            unoptimized
            className="h-auto w-full"
          />
        </div>
      </div>
    </footer>
  );
}
