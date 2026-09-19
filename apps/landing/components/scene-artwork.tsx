import Image from "next/image";
import { FaceOverlay } from "./face-overlay";
import type { FaceOverlaySpec } from "./intro-scene";

const photoSrc = "/intro/photo.png";
const photoSizes = "(max-aspect-ratio: 16/9) 177.7778vh, 100vw";

export function SceneArtwork({
  overlays,
  priority,
}: {
  overlays: FaceOverlaySpec[];
  priority?: boolean;
}) {
  return (
    <>
      <Image
        src={photoSrc}
        alt=""
        width={1672}
        height={941}
        sizes={photoSizes}
        fetchPriority={priority ? "high" : undefined}
        className="absolute inset-0 size-full object-cover"
        aria-hidden="true"
      />
      {overlays.map((overlay) => (
        <FaceOverlay
          key={overlay.mask + overlay.box.l + overlay.box.t}
          overlay={overlay}
        />
      ))}
    </>
  );
}

