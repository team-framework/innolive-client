"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useIsLogined } from "@/hooks/use-is-logined";
import { signOut } from "@/lib/auth-client";

type AuthNavigationProps = {
  className: string;
};

export function AuthNavigation({ className }: AuthNavigationProps) {
  const router = useRouter();
  const { isLogined } = useIsLogined();
  const [isSigningOut, setIsSigningOut] = useState(false);

  if (!isLogined) {
    return (
      <Link href="/login" className={className}>
        Login
      </Link>
    );
  }

  const onSignOut = async () => {
    setIsSigningOut(true);
    try {
      await signOut();
      router.replace("/");
      router.refresh();
    } finally {
      setIsSigningOut(false);
    }
  };

  return (
    <button type="button" className={className} onClick={onSignOut} disabled={isSigningOut}>
      {isSigningOut ? "로그아웃 중" : "Logout"}
    </button>
  );
}
