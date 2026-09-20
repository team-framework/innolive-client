import { TryOutView } from "@/components/try-out-view";
import { getMessages } from "@/lib/messages";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return { title: getMessages(locale).metadata.tryOutTitle };
}

export default function TryOutPage() {
  return (
    <main id="main" data-page="try-out" className="min-h-dvh bg-background-primary">
      <TryOutView />
    </main>
  );
}
