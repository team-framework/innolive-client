"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useLocale } from "@/components/locale-provider";
import { useIsLogined } from "@/hooks/use-is-logined";
import { isInnoliveVideoEnabled } from "@/lib/features";

// Nav entry for InnoLive Video. Renders only when the build-time flag is on AND the visitor is
// logged in, so it never appears in production (flag unset) or for signed-out users. The flag is
// inlined at build, so when off this component tree-shakes to a constant null.
export function VideoNavLink({ className }: { className: string }) {
  const { href, messages } = useLocale();
  const pathname = usePathname();
  const { isLogined } = useIsLogined();

  if (!isInnoliveVideoEnabled() || !isLogined) return null;

  const target = href("/video");
  return (
    <Link href={target} aria-current={pathname === target ? "page" : undefined} className={className}>
      {messages.video.navLabel}
    </Link>
  );
}
