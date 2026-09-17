"use client";

import type { ComponentPropsWithoutRef } from "react";
import { cn } from "@/lib/cn";

const icons = {
  mail: "/icons/mail.svg",
  lock: "/icons/lock-keyhole.svg",
} as const;

export type TextFieldProps = Omit<
  ComponentPropsWithoutRef<"input">,
  "size"
> & {
  icon?: keyof typeof icons;
};

export function TextField({ icon, className, ...props }: TextFieldProps) {
  return (
    <div
      className={cn(
        "flex min-h-[54px] w-full max-w-[512px] items-center gap-3 overflow-hidden rounded-field bg-background-primary px-5 py-[var(--space-14)]",
        className,
      )}
    >
      {icon ? (
        <img
          src={icons[icon]}
          alt=""
          width={24}
          height={24}
          aria-hidden="true"
          className="size-6 shrink-0"
        />
      ) : null}
      <input
        className="min-w-0 flex-1 bg-transparent text-xl font-medium leading-[1.3] text-text-primary placeholder:text-text-secondary"
        {...props}
      />
    </div>
  );
}
