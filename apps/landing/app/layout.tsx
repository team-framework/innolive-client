import type { Metadata } from "next";
import { Footer } from "@/components/footer";
import { Header } from "@/components/header";
import { wantedSans } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
  title: "InnoLive",
  description: "InnoLive landing page",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className={`${wantedSans.variable} ${wantedSans.className}`}>
      <body className="flex min-h-dvh flex-col">
        <Header />
        <div className="flex-1">{children}</div>
        <Footer />
      </body>
    </html>
  );
}
