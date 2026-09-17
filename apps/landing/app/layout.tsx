import type { Metadata } from "next";
import { wantedSans } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
  title: "InnoLive",
  description: "InnoLive landing page",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className={`${wantedSans.variable} ${wantedSans.className}`}>
      <body>{children}</body>
    </html>
  );
}
