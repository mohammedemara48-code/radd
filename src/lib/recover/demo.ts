import type { ScanSource } from "@/lib/recover/engine";
import { publicUrl } from "@/lib/public-url";

async function load(path: string): Promise<Uint8Array> {
  const response = await fetch(publicUrl(path));
  if (!response.ok) throw new Error("missing-demo");
  return new Uint8Array(await response.arrayBuffer());
}

function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.byteLength, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.byteLength;
  }
  return out;
}

function asFile(bytes: Uint8Array, name: string): File {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  return new File([copy], name, { type: "application/octet-stream", lastModified: 1_735_689_600_000 });
}

async function transcode(bytes: Uint8Array, mime: "image/jpeg" | "image/png", maxWidth: number): Promise<Uint8Array> {
  const copy = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(copy).set(bytes);
  const bitmap = await createImageBitmap(new Blob([copy]));
  const scale = Math.min(1, maxWidth / bitmap.width);
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const context = canvas.getContext("2d");
  if (!context) throw new Error("canvas");
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const blob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((value) => (value ? resolve(value) : reject(new Error("blob"))), mime, 0.72);
  });
  return new Uint8Array(await blob.arrayBuffer());
}

export async function buildDemoSources(): Promise<ScanSource[]> {
  const [harbor, dunes, fruit, compass, jasmine, camera] = await Promise.all([
    load("/demo/harbor.jpg"),
    load("/demo/dunes.jpg"),
    load("/demo/pomegranate.jpg"),
    load("/demo/compass.jpg"),
    load("/demo/jasmine.jpg"),
    load("/demo/camera.jpg"),
  ]);

  const [thumb, png] = await Promise.all([
    transcode(jasmine, "image/jpeg", 160),
    transcode(compass, "image/png", 960),
  ]);

  const junkA = new Uint8Array(96);
  const junkB = new Uint8Array(48);
  crypto.getRandomValues(junkA);
  crypto.getRandomValues(junkB);
  junkA[0] = 0x43;
  junkA[1] = 0x41;
  junkA[2] = 0x43;
  junkA[3] = 0x48;

  const packed = concat([
    new TextEncoder().encode("RADD-PACK\n"),
    png,
    new Uint8Array([0, 1, 2, 3, 4]),
  ]);

  const buried = concat([junkA, fruit, junkB]);
  const torn = camera.slice(0, Math.floor(camera.byteLength * 0.28));

  const entries: { path: string; bytes: Uint8Array }[] = [
    { path: "DCIM/Camera/.trashed-1735689600-IMG_4412.jpg", bytes: harbor },
    { path: "LOST.DIR/000004821.chk", bytes: dunes },
    { path: "Android/media/cache/wa-media.bin", bytes: buried },
    { path: "DCIM/Camera/.pending-9912-jasmine.jpg", bytes: jasmine },
    { path: "Download/.compass-scan.jpg", bytes: compass },
    { path: "DCIM/Camera/IMG_1004.jpg", bytes: torn },
    { path: "DCIM/.thumbnails/.thumbdata-77.jpg", bytes: thumb },
    { path: "backup/album.pack", bytes: packed },
    { path: "DCIM/Camera/IMG_0900.jpg", bytes: camera },
  ];

  return entries.map((entry) => {
    const name = entry.path.split("/").pop() || "file.bin";
    return { file: asFile(entry.bytes, name), path: entry.path };
  });
}
