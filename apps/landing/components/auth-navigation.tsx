"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useLocale } from "@/components/locale-provider";
import { useIsLogined } from "@/hooks/use-is-logined";
import { signOut } from "@/lib/auth-client";

type AuthNavigationProps = {
  className: string;
};

export function AuthNavigation({ className }: AuthNavigationProps) {
  const router = useRouter();
  const { href, messages } = useLocale();
  const { isLogined } = useIsLogined();
  const [isSigningOut, setIsSigningOut] = useState(false);

  if (!isLogined) {
    return (
      <Link href={href("/login")} className={className}>
        {messages.authNav.login}
      </Link>
    );
  }

  const onSignOut = async () => {
    setIsSigningOut(true);
    try {
      await signOut();
      router.replace(href("/"));
      router.refresh();
    } finally {
      setIsSigningOut(false);
    }
  };

  return (
    <button type="button" className={className} onClick={onSignOut} disabled={isSigningOut}>
      {isSigningOut ? messages.authNav.signingOut : messages.authNav.logout}
    </button>
  );
}
