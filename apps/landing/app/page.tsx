import { FeatureSection } from "@/components/feature-section";
import { Hero } from "@/components/hero";
import { PrivacySection } from "@/components/privacy-section";

export default function Home() {
  return (
    <main id="main" data-page="home" className="bg-background-primary">
      <Hero />
      <PrivacySection />
      <FeatureSection />
    </main>
  );
}
