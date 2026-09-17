import localFont from "next/font/local";

export const wantedSans = localFont({
  src: "./fonts/wanted-sans/WantedSansVariable.woff2",
  display: "swap",
  weight: "400 1000",
  variable: "--font-wanted-sans",
  fallback: ["system-ui", "sans-serif"],
});
