import type { Metadata } from "next";
import { TryOutExperience } from "@/components/try-out-experience";

export const metadata: Metadata = {
  title: "InnoLive 체험",
};

export default function TryOutExperiencePage() {
  return (
    <main id="main" data-page="try-out" className="min-h-dvh bg-background-primary">
      <TryOutExperience />
    </main>
  );
}
