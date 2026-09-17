"use client";

import Image from "next/image";
import { useState } from "react";
import { Dialog } from "@/components/dialog";
import { OnDeviceOverlay } from "@/components/on-device-overlay";

export function OnDeviceHelp({ label }: { label: string }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        className="relative size-7 shrink-0"
        aria-label={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <Image
          src="/icons/circle-question.svg"
          alt=""
          width={28}
          height={28}
          unoptimized
          className="size-7"
        />
      </button>
      <Dialog open={open} onClose={() => setOpen(false)} label="On Device">
        <OnDeviceOverlay />
      </Dialog>
    </>
  );
}
