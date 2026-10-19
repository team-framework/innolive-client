import type { ComponentProps } from "react";
import artworkSources from "@/lib/static-artwork.json";

type StaticArtworkProps = Omit<ComponentProps<"img">, "src" | "srcSet" | "width" | "height"> & {
  artwork: keyof typeof artworkSources;
  alt: string;
};

export function StaticArtwork({ artwork, alt, loading = "lazy", ...props }: StaticArtworkProps) {
  const image = artworkSources[artwork];
  return (
    // These versioned WebP variants are generated ahead of time, with native responsive selection.
    // eslint-disable-next-line @next/next/no-img-element
    <img {...props} {...image} alt={alt} loading={loading} decoding="async" />
  );
}
