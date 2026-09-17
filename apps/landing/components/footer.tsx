import Image from "next/image";
import Link from "next/link";
import { socialLinks } from "@/lib/site";

export function Footer() {
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
            />
            <p className="text-lg leading-[1.15] text-text-primary">
              실시간 AI 비식별화 방송 솔루션
            </p>
          </div>
          <div className="flex flex-wrap items-end justify-between gap-x-8 gap-y-6">
            <div className="flex min-w-0 max-w-[1100px] flex-1 flex-col gap-1.5 text-base leading-[1.3] tracking-[-0.02em] text-text-secondary">
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2">
                <p>Framework 대표: 채근영</p>
                <p>개인정보보호책임자: 권대형</p>
                <a className="underline" href="mailto:contact@innolive.studio">
                  contact@innolive.studio
                </a>
                <p>Hosted by Framework</p>
              </div>
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2">
                <p>전화번호: 010-2732-9514</p>
                <p>주소: 대구광역시 달성군 구지면 창리로11길 93</p>
                <p>Copyright © 2026 Framework</p>
              </div>
              <div className="flex flex-wrap items-center gap-x-2.5 gap-y-2 text-text-secondary">
                <Link href="/privacy" className="font-semibold underline">
                  개인정보 처리방침
                </Link>
                <Link href="/terms" className="underline">
                  서비스 이용약관
                </Link>
                <Link href="/#faq" className="underline">
                  FAQ
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
                      aria-label={`${social.name} 링크는 아직 없습니다`}
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
