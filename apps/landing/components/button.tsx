"use client";

import Image from "next/image";
import Link from "next/link";
import type { ComponentPropsWithoutRef, ReactNode, Ref } from "react";
import { cn } from "@/lib/cn";

const chevrons = {
  primary: "/icons/chevron-down.svg",
  secondary: "/icons/chevron-down-reversed.svg",
  disabled: "/icons/chevron-down-disabled.svg",
} as const;

const baseClass =
  "inline-flex max-w-[358.4px] min-h-11 min-w-[70px] items-center justify-center gap-[8.4px] overflow-hidden rounded-pill px-[22.4px] py-[9.91px] text-left text-xl font-semibold leading-none shadow-button transition-colors md:max-w-[512px] md:min-h-14 md:min-w-[100px] md:gap-3 md:px-8 md:py-[var(--space-18)]";

const variantClass = {
  primary:
    "bg-button-primary text-text-primary hover:bg-button-primary-hover active:bg-button-primary-pressed disabled:cursor-not-allowed disabled:opacity-60 aria-disabled:cursor-not-allowed aria-disabled:pointer-events-none aria-disabled:opacity-60",
  secondary:
    "bg-button-secondary text-text-reversed hover:bg-button-secondary-hover active:bg-button-secondary-pressed disabled:cursor-not-allowed disabled:bg-button-secondary-disabled disabled:text-text-secondary disabled:opacity-100 aria-disabled:cursor-not-allowed aria-disabled:pointer-events-none aria-disabled:bg-button-secondary-disabled aria-disabled:text-text-secondary aria-disabled:opacity-100",
} as const;

type Variant = keyof typeof variantClass;

type CommonProps = {
  variant?: Variant;
  showChevron?: boolean;
  children: ReactNode;
  className?: string;
  disabled?: boolean;
  ref?: Ref<HTMLButtonElement>;
};

type ButtonAsButton = CommonProps &
  Omit<
    ComponentPropsWithoutRef<"button">,
    "children" | "className" | "disabled"
  > & {
    href?: undefined;
  };

type ButtonAsLink = CommonProps &
  Omit<
    ComponentPropsWithoutRef<"a">,
    "children" | "className" | "href" | "disabled"
  > & {
    href: string;
  };

export type ButtonProps = ButtonAsButton | ButtonAsLink;

function isInternalHref(href: string) {
  return href.startsWith("/") && !href.startsWith("//");
}

function Chevron({ variant, disabled }: { variant: Variant; disabled: boolean }) {
  const src = disabled
    ? chevrons.disabled
    : variant === "secondary"
      ? chevrons.secondary
      : chevrons.primary;

  return (
    <Image
      src={src}
      alt=""
      width={24}
      height={24}
      unoptimized
      aria-hidden="true"
      className="size-6 shrink-0"
    />
  );
}

export function Button({
  variant = "primary",
  showChevron = true,
  className,
  children,
  disabled = false,
  href,
  ref,
  ...rest
}: ButtonProps) {
  const classes = cn(baseClass, variantClass[variant], className);
  const content = (
    <>
      <span className="min-w-0">{children}</span>
      {showChevron ? <Chevron variant={variant} disabled={disabled} /> : null}
    </>
  );

  if (href) {
    if (disabled) {
      return (
        <span className={classes} role="link" aria-disabled="true">
          {content}
        </span>
      );
    }

    const linkProps = rest as Omit<ButtonAsLink, keyof CommonProps | "href">;

    if (isInternalHref(href)) {
      return (
        <Link href={href} className={classes} {...linkProps}>
          {content}
        </Link>
      );
    }

    return (
      <a href={href} className={classes} {...linkProps}>
        {content}
      </a>
    );
  }

  const buttonProps = rest as Omit<ButtonAsButton, keyof CommonProps | "href">;

  return (
    <button
      ref={ref}
      type="button"
      className={classes}
      disabled={disabled}
      {...buttonProps}
    >
      {content}
    </button>
  );
}
