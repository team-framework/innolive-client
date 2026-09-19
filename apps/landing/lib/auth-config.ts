const allowedProtocols = new Set(["http:", "https:"]);

export function getInnoLiveServerUrl() {
  const value = process.env.NEXT_PUBLIC_INNOLIVE_SERVER_URL?.trim();
  if (!value) {
    throw new Error("NEXT_PUBLIC_INNOLIVE_SERVER_URL is not configured");
  }

  const url = new URL(value);
  if (!allowedProtocols.has(url.protocol)) {
    throw new Error("NEXT_PUBLIC_INNOLIVE_SERVER_URL must use http or https");
  }

  return url.toString().replace(/\/$/, "");
}

export function utf8ByteLength(value: string) {
  return new TextEncoder().encode(value).length;
}
