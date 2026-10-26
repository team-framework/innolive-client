import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { VideoPage } from "@/components/video/video-page";
import { accessTokenIsFresh, readAuthCookies } from "@/lib/auth-server";
import { isInnoliveVideoEnabled } from "@/lib/features";
import { defaultLocale, isLocale, localePath } from "@/lib/locales";
import { getMessages } from "@/lib/messages";

type PageProps = {
  params: Promise<{ locale: string }>;
};

// Private, logged-in-only page: always noindex and never in the sitemap (sitemapPaths omits it).
export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale } = await params;
  const { video } = getMessages(locale);
  return {
    title: video.title,
    description: video.subtitle,
    robots: { index: false, follow: false },
  };
}

export default async function VideoRoutePage({ params }: PageProps) {
  const { locale } = await params;
  // Feature flag off (e.g. production, where it is never set) -> the route does not exist.
  if (!isInnoliveVideoEnabled()) notFound();

  const resolvedLocale = isLocale(locale) ? locale : defaultLocale;

  // Server-side auth gate. A fresh access token (or a refresh token to mint one) is required;
  // otherwise send the visitor to login. Mirrors /api/auth/session's freshness check.
  const { access, refresh } = await readAuthCookies();
  if (!accessTokenIsFresh(access) && !refresh) {
    redirect(localePath(resolvedLocale, "/login"));
  }

  return <VideoPage />;
}
