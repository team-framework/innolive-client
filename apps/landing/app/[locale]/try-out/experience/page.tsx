import { TryOutExperience } from "@/components/try-out-experience";
import { getMessages } from "@/lib/messages";
import { pageMetadata } from "@/lib/seo";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  const { metadata } = getMessages(locale);
  return pageMetadata(locale, "/try-out/experience", {
    title: metadata.experienceTitle,
    description: metadata.description,
  });
}

export default function TryOutExperiencePage() {
  return (
    <main id="main" data-page="try-out" className="min-h-dvh bg-background-primary">
      <TryOutExperience />
    </main>
  );
}
