const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function copyBytes(data: Uint8Array): ArrayBuffer {
  const copy = new ArrayBuffer(data.byteLength);
  new Uint8Array(copy).set(data);
  return copy;
}

export async function zipBlobs(files: { name: string; blob: Blob }[]): Promise<Blob> {
  const locals: ArrayBuffer[] = [];
  const central: ArrayBuffer[] = [];
  let offset = 0;

  for (const file of files) {
    const data = new Uint8Array(await file.blob.arrayBuffer());
    const name = new TextEncoder().encode(file.name.replace(/[\\/]/g, "_"));
    const crc = crc32(data);

    const local = new Uint8Array(30 + name.length);
    const view = new DataView(local.buffer);
    view.setUint32(0, 0x04034b50, true);
    view.setUint16(4, 20, true);
    view.setUint16(6, 0x0800, true);
    view.setUint16(8, 0, true);
    view.setUint32(14, crc, true);
    view.setUint32(18, data.length, true);
    view.setUint32(22, data.length, true);
    view.setUint16(26, name.length, true);
    local.set(name, 30);

    const cen = new Uint8Array(46 + name.length);
    const cenView = new DataView(cen.buffer);
    cenView.setUint32(0, 0x02014b50, true);
    cenView.setUint16(4, 20, true);
    cenView.setUint16(6, 20, true);
    cenView.setUint16(8, 0x0800, true);
    cenView.setUint16(10, 0, true);
    cenView.setUint32(16, crc, true);
    cenView.setUint32(20, data.length, true);
    cenView.setUint32(24, data.length, true);
    cenView.setUint16(28, name.length, true);
    cenView.setUint32(42, offset, true);
    cen.set(name, 46);

    locals.push(local.buffer, copyBytes(data));
    central.push(cen.buffer);
    offset += local.length + data.length;
  }

  const centralSize = central.reduce((sum, part) => sum + part.byteLength, 0);
  const eocd = new Uint8Array(22);
  const endView = new DataView(eocd.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(8, files.length, true);
  endView.setUint16(10, files.length, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, offset, true);

  return new Blob([...locals, ...central, eocd.buffer], { type: "application/zip" });
}
