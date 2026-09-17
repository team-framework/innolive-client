import { FAQSection } from "@/components/faq-section";
import { FeatureSection } from "@/components/feature-section";
import { Header } from "@/components/header";
import { Hero } from "@/components/hero";
import { IntroScroll } from "@/components/intro-scroll";
import { PrivacySection } from "@/components/privacy-section";

export default function Home() {
  return (
    <>
      <IntroScroll />
      <Header />
      <main
        id="main"
        tabIndex={-1}
        data-page="home"
        className="bg-background-primary"
      >
        <Hero />
        <PrivacySection />
        <FeatureSection />
        <FAQSection />
      </main>
    </>
  );
}
