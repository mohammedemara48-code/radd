export type ImageFormat = "jpeg" | "png" | "webp" | "gif" | "bmp" | "heic" | "avif";

export type Reason =
  | "trashed"
  | "pending"
  | "hidden"
  | "lostdir"
  | "badext"
  | "carved"
  | "truncated"
  | "thumbnail"
  | "intact";

export type RawCarve = {
  format: ImageFormat;
  offset: number;
  end: number;
  complete: boolean;
};

export type ScanSource = { file: File; path: string };

export type Hit = {
  id: string;
  path: string;
  restoreName: string;
  reason: Reason;
  format: ImageFormat;
  confidence: number;
  bytes: number;
  offset: number;
  width: number;
  height: number;
  small: boolean;
  blob: Blob;
  previewUrl: string;
};

export type Progress = { done: number; total: number; path: string; found: number };

export const REASON_LABEL: Record<Reason, string> = {
  trashed: "سلة الحذف",
  pending: "حفظ ناقص",
  hidden: "ملف مخفي",
  lostdir: "LOST.DIR",
  badext: "امتداد ضايع",
  carved: "منحوت",
  truncated: "مبتور",
  thumbnail: "مصغّرة",
  intact: "سليمة",
};

export const FORMAT_LABEL: Record<ImageFormat, string> = {
  jpeg: "JPG",
  png: "PNG",
  webp: "WEBP",
  gif: "GIF",
  bmp: "BMP",
  heic: "HEIC",
  avif: "AVIF",
};

const MIME: Record<ImageFormat, string> = {
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  gif: "image/gif",
  bmp: "image/bmp",
  heic: "image/heic",
  avif: "image/avif",
};

const FORMAT_EXTS: Record<ImageFormat, string[]> = {
  jpeg: ["jpg", "jpeg"],
  png: ["png"],
  webp: ["webp"],
  gif: ["gif"],
  bmp: ["bmp"],
  heic: ["heic", "heif"],
  avif: ["avif"],
};

const REASON_ORDER: Record<Reason, number> = {
  trashed: 0,
  lostdir: 1,
  carved: 2,
  badext: 3,
  pending: 4,
  hidden: 5,
  thumbnail: 6,
  truncated: 7,
  intact: 8,
};

export const MAX_BYTES = 18 * 1024 * 1024;
export const MAX_FILES = 700;

export function carveImages(data: Uint8Array, deep: boolean): RawCarve[] {
  const hits: RawCarve[] = [];

  const overlaps = (offset: number, end: number) =>
    hits.some((h) => offset < h.end && end > h.offset);

  const add = (hit: RawCarve | null) => {
    if (!hit || hit.end <= hit.offset) return;
    const span = hit.end - hit.offset;
    if (!hit.complete && span < 8192) return;
    if (overlaps(hit.offset, hit.end)) return;
    hits.push(hit);
  };

  add(sniff(data, 0));

  if (deep) {
    const n = data.length;
    for (let i = 1; i < n - 12 && hits.length < 6; i++) {
      const covering = hits.find((h) => i >= h.offset && i < h.end);
      if (covering) {
        i = covering.end - 1;
        continue;
      }
      const b0 = data[i];
      if (b0 === 0xff && data[i + 1] === 0xd8 && data[i + 2] === 0xff) {
        add(parseJpeg(data, i));
        continue;
      }
      if (b0 === 0x89 && data[i + 1] === 0x50 && data[i + 2] === 0x4e && data[i + 3] === 0x47) {
        add(parsePng(data, i));
        continue;
      }
      if (b0 === 0x52 && data[i + 1] === 0x49 && data[i + 2] === 0x46 && data[i + 3] === 0x46) {
        add(parseWebp(data, i));
      }
    }
  }

  return hits;
}

function sniff(data: Uint8Array, offset: number): RawCarve | null {
  return (
    parseJpeg(data, offset) ||
    parsePng(data, offset) ||
    parseWebp(data, offset) ||
    parseGif(data, offset) ||
    parseBmp(data, offset) ||
    parseIso(data, offset)
  );
}

function parseJpeg(data: Uint8Array, offset: number): RawCarve | null {
  if (offset + 4 > data.length) return null;
  if (data[offset] !== 0xff || data[offset + 1] !== 0xd8 || data[offset + 2] !== 0xff) return null;

  let i = offset + 2;
  let sawSos = false;

  while (i < data.length) {
    if (data[i] !== 0xff) return sawSos ? hit("jpeg", offset, data.length, false) : null;
    while (i < data.length && data[i] === 0xff) i++;
    if (i >= data.length) return hit("jpeg", offset, data.length, false);
    const marker = data[i++];
    if (marker === 0xd9) return hit("jpeg", offset, i, true);
    if (marker === 0x00 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (i + 1 >= data.length) return hit("jpeg", offset, data.length, false);
    const len = (data[i] << 8) | data[i + 1];
    if (len < 2) return null;
    if (i + len > data.length) return sawSos || marker !== 0xda ? hit("jpeg", offset, data.length, false) : null;
    if (marker === 0xda) {
      sawSos = true;
      i += len;
      while (i < data.length) {
        if (data[i] !== 0xff) {
          i++;
          continue;
        }
        let j = i + 1;
        while (j < data.length && data[j] === 0xff) j++;
        if (j >= data.length) return hit("jpeg", offset, data.length, false);
        const next = data[j];
        if (next === 0x00 || (next >= 0xd0 && next <= 0xd7)) {
          i = j + 1;
          continue;
        }
        i = j - 1;
        break;
      }
      if (i >= data.length) return hit("jpeg", offset, data.length, false);
      continue;
    }
    i += len;
  }

  return sawSos ? hit("jpeg", offset, data.length, false) : null;
}

function parsePng(data: Uint8Array, offset: number): RawCarve | null {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (offset + 8 > data.length) return null;
  for (let k = 0; k < 8; k++) if (data[offset + k] !== sig[k]) return null;
  let i = offset + 8;
  while (i + 8 <= data.length) {
    const len =
      ((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]) >>> 0;
    if (len > 24_000_000) return null;
    const type = String.fromCharCode(data[i + 4], data[i + 5], data[i + 6], data[i + 7]);
    const next = i + 12 + len;
    if (next > data.length) return hit("png", offset, data.length, false);
    i = next;
    if (type === "IEND") return hit("png", offset, i, true);
  }
  return i > offset + 8 ? hit("png", offset, data.length, false) : null;
}

function parseWebp(data: Uint8Array, offset: number): RawCarve | null {
  if (offset + 12 > data.length) return null;
  if (data[offset] !== 0x52 || data[offset + 1] !== 0x49 || data[offset + 2] !== 0x46 || data[offset + 3] !== 0x46)
    return null;
  if (
    data[offset + 8] !== 0x57 ||
    data[offset + 9] !== 0x45 ||
    data[offset + 10] !== 0x42 ||
    data[offset + 11] !== 0x50
  )
    return null;
  const size =
    data[offset + 4] |
    (data[offset + 5] << 8) |
    (data[offset + 6] << 16) |
    (data[offset + 7] << 24);
  const total = 8 + size;
  if (total < 12 || total > 40_000_000) return null;
  const end = offset + total;
  if (end > data.length) return hit("webp", offset, data.length, false);
  return hit("webp", offset, end, true);
}

function parseGif(data: Uint8Array, offset: number): RawCarve | null {
  if (offset !== 0 || data.length < 6) return null;
  const head = String.fromCharCode(data[0], data[1], data[2], data[3], data[4], data[5]);
  if (head !== "GIF87a" && head !== "GIF89a") return null;
  return hit("gif", 0, data.length, data[data.length - 1] === 0x3b);
}

function parseBmp(data: Uint8Array, offset: number): RawCarve | null {
  if (offset !== 0 || data.length < 26) return null;
  if (data[0] !== 0x42 || data[1] !== 0x4d) return null;
  const size = data[2] | (data[3] << 8) | (data[4] << 16) | (data[5] << 24);
  const pixelOff = data[10] | (data[11] << 8) | (data[12] << 16) | (data[13] << 24);
  if (size < 54 || pixelOff < 14 || pixelOff >= size) return null;
  if (size > data.length) return hit("bmp", 0, data.length, false);
  return hit("bmp", 0, size, true);
}

function parseIso(data: Uint8Array, offset: number): RawCarve | null {
  if (offset !== 0 || data.length < 12) return null;
  if (data[4] !== 0x66 || data[5] !== 0x74 || data[6] !== 0x79 || data[7] !== 0x70) return null;
  const brand = String.fromCharCode(data[8], data[9], data[10], data[11]).toLowerCase();
  const heic = brand === "heic" || brand === "heix" || brand === "hevc" || brand === "heif" || brand === "mif1" || brand === "msf1";
  const avif = brand === "avif" || brand === "avis";
  if (!heic && !avif) return null;
  return hit(avif ? "avif" : "heic", 0, data.length, true);
}

function hit(format: ImageFormat, offset: number, end: number, complete: boolean): RawCarve {
  return { format, offset, end, complete };
}

export function suggestName(path: string, format: ImageFormat, index: number): string {
  const base = path.split("/").pop() || "";
  let name = base;
  const trashed = name.match(/^\.trashed-\d+-(.+)$/i);
  if (trashed?.[1]) name = trashed[1];
  const pending = name.match(/^\.pending-\d+-(.+)$/i);
  if (pending?.[1]) name = pending[1];
  if (name.startsWith(".")) name = name.slice(1);
  name = name.replace(/[^\w.\-\u0600-\u06FF]+/g, "_").replace(/_+/g, "_");
  const ext = FORMAT_EXTS[format][0] === "jpeg" ? "jpg" : FORMAT_EXTS[format][0];
  name = name.replace(/\.[^.]+$/i, "").replace(/^\.+/, "");
  name = `${name || "photo"}.${ext}`;
  if (name === `.${ext}`) name = `recovered-${index}.${ext}`;
  return name;
}

function extOf(path: string): string {
  const base = path.split("/").pop() || "";
  const i = base.lastIndexOf(".");
  if (i <= 0) return "";
  return base.slice(i + 1).toLowerCase();
}

function nameReason(path: string): Reason | null {
  const base = (path.split("/").pop() || "").toLowerCase();
  const lower = path.toLowerCase();
  if (base.startsWith(".trashed-")) return "trashed";
  if (base.startsWith(".pending-")) return "pending";
  if (lower.includes("lost.dir")) return "lostdir";
  if (
    lower.includes(".thumbnails") ||
    base.startsWith(".thumb") ||
    base.startsWith("thumbdata") ||
    base.startsWith("thumb_")
  )
    return "thumbnail";
  if (base.startsWith(".")) return "hidden";
  return null;
}

function decideReason(
  path: string,
  format: ImageFormat,
  offset: number,
  complete: boolean,
  decoded: boolean,
): Reason {
  if (!complete) return offset > 0 ? "carved" : "truncated";
  if (offset > 0) return "carved";
  const named = nameReason(path);
  if (named) return named;
  if (!FORMAT_EXTS[format].includes(extOf(path))) return "badext";
  if (!decoded) return "truncated";
  return "intact";
}

function score(reason: Reason, decoded: boolean, complete: boolean, small: boolean): number {
  if (!decoded && !complete) return 31;
  if (!decoded) return 52;
  let s = 90;
  if (!complete) s -= 22;
  if (reason === "carved") s -= 5;
  if (reason === "badext" || reason === "hidden" || reason === "pending") s -= 2;
  if (reason === "trashed" || reason === "lostdir") s += 4;
  if (small || reason === "thumbnail") s -= 7;
  if (reason === "truncated") s -= 18;
  return Math.max(15, Math.min(97, s));
}

function bufferCopy(data: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(data.byteLength);
  new Uint8Array(copy).set(data);
  return copy;
}

async function decodeSize(blob: Blob): Promise<{ width: number; height: number } | null> {
  try {
    if (typeof createImageBitmap !== "function") return null;
    const bmp = await createImageBitmap(blob);
    const width = bmp.width;
    const height = bmp.height;
    bmp.close();
    if (width < 1 || height < 1) return null;
    return { width, height };
  } catch {
    return null;
  }
}

function uniqueName(name: string, used: Set<string>): string {
  if (!used.has(name)) {
    used.add(name);
    return name;
  }
  const dot = name.lastIndexOf(".");
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : "";
  let n = 2;
  let next = `${stem}-${n}${ext}`;
  while (used.has(next)) {
    n += 1;
    next = `${stem}-${n}${ext}`;
  }
  used.add(next);
  return next;
}

export async function scanSources(
  sources: ScanSource[],
  options: {
    deep: boolean;
    signal?: AbortSignal;
    onProgress?: (progress: Progress) => void;
  },
): Promise<{ hits: Hit[]; scanned: number; skipped: number; cancelled: boolean }> {
  const queue: ScanSource[] = [];
  let skipped = 0;
  for (const source of sources) {
    if (source.file.size <= 0 || source.file.size > MAX_BYTES) {
      skipped += 1;
      continue;
    }
    if (queue.length >= MAX_FILES) {
      skipped += 1;
      continue;
    }
    queue.push(source);
  }

  const hits: Hit[] = [];
  let cancelled = false;

  for (let index = 0; index < queue.length; index++) {
    if (options.signal?.aborted) {
      cancelled = true;
      break;
    }
    const source = queue[index];
    options.onProgress?.({
      done: index,
      total: queue.length,
      path: source.path,
      found: hits.length,
    });

    try {
      const data = new Uint8Array(await source.file.slice(0, MAX_BYTES).arrayBuffer());
      const carved = carveImages(data, options.deep);
      for (const piece of carved) {
        const slice = data.subarray(piece.offset, piece.end);
        const blob = new Blob([bufferCopy(slice)], { type: MIME[piece.format] });
        const decoded = await decodeSize(blob);
        const reason = decideReason(source.path, piece.format, piece.offset, piece.complete, decoded !== null);
        const small = decoded !== null && decoded.width <= 480 && decoded.height <= 480 && slice.byteLength < 80_000;
        const confidence = score(reason, decoded !== null, piece.complete, small);
        if (!decoded && !piece.complete && slice.byteLength < 8192) continue;
        hits.push({
          id: `${source.path}#${piece.offset}`,
          path: source.path,
          restoreName: "",
          reason,
          format: piece.format,
          confidence,
          bytes: slice.byteLength,
          offset: piece.offset,
          width: decoded?.width ?? 0,
          height: decoded?.height ?? 0,
          small,
          blob,
          previewUrl: decoded ? URL.createObjectURL(blob) : "",
        });
      }
    } catch {
      skipped += 1;
    }

    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  options.onProgress?.({
    done: cancelled ? hits.length : queue.length,
    total: queue.length,
    path: "",
    found: hits.length,
  });

  hits.sort((a, b) => REASON_ORDER[a.reason] - REASON_ORDER[b.reason] || b.confidence - a.confidence);
  const used = new Set<string>();
  hits.forEach((item, index) => {
    item.restoreName = uniqueName(suggestName(item.path, item.format, index + 1), used);
  });

  return { hits, scanned: queue.length, skipped, cancelled };
}

export function revokeHits(hits: Hit[]) {
  for (const hit of hits) {
    if (hit.previewUrl) URL.revokeObjectURL(hit.previewUrl);
  }
}
