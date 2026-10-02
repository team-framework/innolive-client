"use client";

import type { ReactNode } from "react";
import { useLocale } from "@/components/locale-provider";

export function TryOutVideoPanels({
  local,
  processed,
}: {
  local: ReactNode;
  processed: ReactNode;
}) {
  const { messages } = useLocale();
  const copy = messages.experience;

  return (
    <div className="grid w-full max-w-[100rem] grid-cols-1 gap-3 lg:grid-cols-2">
      <div className="relative aspect-video w-full overflow-hidden rounded-[12px] bg-background-secondary">
        {local}
        <p className="absolute bottom-3 left-3 rounded-pill bg-background-primary/80 px-3 py-1 text-sm text-text-primary">
          {copy.localBadge}
        </p>
      </div>
      <div className="relative aspect-video w-full overflow-hidden rounded-[12px] bg-background-secondary">
        {processed}
        <p className="absolute bottom-3 left-3 rounded-pill bg-background-primary/80 px-3 py-1 text-sm text-text-primary">
          {copy.remoteBadge}
        </p>
      </div>
    </div>
  );
}
