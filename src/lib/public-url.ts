import { previewPathPrefix } from "@/lib/preview-path-prefix";

/** Public asset URL that still resolves under a path-prefixed preview host. */
export function publicUrl(path: string): string {
  if (typeof window === "undefined") return path;
  const prefix = previewPathPrefix(window.location.hostname, window.location.pathname) ?? "";
  return `${prefix}${path}`;
}
