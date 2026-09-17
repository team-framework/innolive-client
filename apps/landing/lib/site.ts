export type DownloadPlatform = {
  id: "android" | "ios";
  name: string;
  minOs: string;
  iconSrc: string;
  iconWidth: number;
  iconHeight: number;
  href: string | null;
};

function envUrl(name: string): string | null {
  const value = process.env[name]?.trim();
  return value ? value : null;
}

export const downloadPlatforms: DownloadPlatform[] = [
  {
    id: "android",
    name: "Android",
    minOs: "Android 12+",
    iconSrc: "/icons/android-head.svg",
    iconWidth: 26,
    iconHeight: 15,
    href: envUrl("NEXT_PUBLIC_ANDROID_DOWNLOAD_URL"),
  },
  {
    id: "ios",
    name: "iOS",
    minOs: "iOS 26+",
    iconSrc: "/icons/apple.svg",
    iconWidth: 26,
    iconHeight: 26,
    href: envUrl("NEXT_PUBLIC_IOS_DOWNLOAD_URL"),
  },
];

export const navLinks = [
  { href: "/try-out", label: "Try out" },
  { href: "/pricing", label: "Pricing" },
] as const;

export const socialLinks = [
  {
    name: "LinkedIn",
    src: "/social/linkedin.png",
    href: null as string | null,
  },
  {
    name: "GitHub",
    src: "/social/github.png",
    href: "https://github.com/team-framework",
  },
  {
    name: "YouTube",
    src: "/social/youtube.png",
    href: null as string | null,
  },
] as const;
