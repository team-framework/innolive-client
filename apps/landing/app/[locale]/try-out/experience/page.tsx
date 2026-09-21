import { TryOutExperience } from "@/components/try-out-experience";
import { getMessages } from "@/lib/messages";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return { title: getMessages(locale).metadata.experienceTitle };
}

export default function TryOutExperiencePage() {
  return (
    <main id="main" data-page="try-out" className="min-h-dvh bg-background-primary">
      <TryOutExperience />
    </main>
  );
}
