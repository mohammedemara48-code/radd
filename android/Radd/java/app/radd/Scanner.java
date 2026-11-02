package app.radd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Scanner {
    static final class Hit {
        final String restoreName;
        final String reason;
        final String sourcePath;
        final File cacheFile;
        final int confidence;
        final String detail;

        Hit(String restoreName, String reason, String sourcePath, File cacheFile, int confidence, String detail) {
            this.restoreName = restoreName;
            this.reason = reason;
            this.sourcePath = sourcePath;
            this.cacheFile = cacheFile;
            this.confidence = confidence;
            this.detail = detail;
        }
    }

    interface Progress {
        void onProgress(int seen, int found, String path);
    }

    private static final int MAX_READ = 12 * 1024 * 1024;
    private static final int MAX_FILES = 8000;
    private static final int MAX_HITS = 400;
    private static final Set<String> IMAGE_EXT = new HashSet<String>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"));
    private static final Set<String> SKIP_EXT = new HashSet<String>(Arrays.asList(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "mp3", "aac", "m4a", "ogg", "wav",
            "apk", "zip", "rar", "7z", "obb", "iso", "exe"));

    int skippedIntact;

    List<Hit> scan(File root, File cacheDir, boolean deep, Progress progress, boolean[] stop) {
        skippedIntact = 0;
        List<Hit> hits = new ArrayList<Hit>();
        List<File> files = new ArrayList<File>();
        collect(root, files, stop);
        int index = 0;
        for (int i = 0; i < files.size(); i++) {
            if (stop[0] || hits.size() >= MAX_HITS) break;
            File file = files.get(i);
            if (i % 12 == 0) progress.onProgress(i, hits.size(), file.getPath());
            try {
                inspect(file, cacheDir, deep, hits, index);
            } catch (IOException ignored) {
            } catch (OutOfMemoryError ignored) {
            }
            index = hits.size();
        }
        progress.onProgress(files.size(), hits.size(), "");
        return hits;
    }

    private void collect(File dir, List<File> out, boolean[] stop) {
        if (stop[0] || out.size() >= MAX_FILES || dir == null) return;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (SecurityException ex) {
            return;
        }
        if (kids == null) return;
        for (File kid : kids) {
            if (stop[0] || out.size() >= MAX_FILES) return;
            String name = kid.getName();
            if ("Android".equals(name) && kid.getParentFile() != null
                    && "0".equals(kid.getParentFile().getName())) {
                File media = new File(kid, "media");
                collect(media, out, stop);
                continue;
            }
            if ("RaddRecovered".equals(name)) continue;
            if (kid.isDirectory()) collect(kid, out, stop);
            else if (kid.isFile() && kid.length() > 0 && kid.length() <= 40L * 1024 * 1024) out.add(kid);
        }
    }

    private void inspect(File file, File cacheDir, boolean deep, List<Hit> hits, int nameSeed) throws IOException {
        int size = (int) Math.min(file.length(), MAX_READ);
        boolean suspicious = suspicious(file);
        boolean imageExt = maybeImageExt(file);
        String ext = extension(file.getName().toLowerCase(Locale.US));
        if (!suspicious && !imageExt) {
            if (!deep || SKIP_EXT.contains(ext) || file.length() > 4L * 1024 * 1024) return;
        }
        if (!suspicious && imageExt) {
            byte[] head = read(file, (int) Math.min(file.length(), 16));
            if (isNormalImage(head)) {
                skippedIntact++;
                return;
            }
        }
        byte[] data = read(file, size);
        if (data == null || data.length < 12) return;
        List<Carve> pieces = carve(data, deep || suspicious);
        String path = file.getAbsolutePath();
        for (Carve piece : pieces) {
            if (hits.size() >= MAX_HITS) return;
            boolean decodedHeader = piece.complete || piece.end - piece.offset > 8192;
            if (!decodedHeader) continue;
            String reason = reason(path, piece);
            if ("intact".equals(reason)) {
                skippedIntact++;
                continue;
            }
            int len = piece.end - piece.offset;
            File out = new File(cacheDir, "hit-" + hits.size() + "." + extOf(piece.format));
            writeSlice(out, data, piece.offset, len);
            String restore = suggest(path, piece.format, nameSeed + hits.size() + 1);
            int confidence = score(reason, piece.complete);
            String detail = piece.format.toUpperCase(Locale.US) + " · " + kb(len)
                    + (piece.offset > 0 ? " · من بايت " + piece.offset : "");
            hits.add(new Hit(restore, label(reason), path, out, confidence, detail));
        }
    }

    private static boolean suspicious(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        String path = file.getPath().toLowerCase(Locale.US);
        if (name.startsWith(".")) return true;
        if (name.startsWith(".trashed-") || name.contains(".trashed-") || name.startsWith(".pending-")) return true;
        if (path.contains("lost.dir") || path.contains(".thumbnails") || path.contains("/cache/") || path.contains("thumb"))
            return true;
        String ext = extension(name);
        return ext.isEmpty() || "chk".equals(ext) || "bin".equals(ext) || "dat".equals(ext) || "tmp".equals(ext);
    }

    private static boolean isNormalImage(byte[] head) {
        if (head == null || head.length < 3) return false;
        int b0 = head[0] & 0xff;
        int b1 = head[1] & 0xff;
        if (b0 == 0xff && b1 == 0xd8) return true;
        if (head.length >= 8 && b0 == 0x89 && b1 == 0x50 && (head[2] & 0xff) == 0x4e) return true;
        if (head.length >= 12 && b0 == 0x52 && b1 == 0x49 && (head[8] & 0xff) == 0x57) return true;
        return false;
    }

    private static boolean maybeImageExt(File file) {
        return IMAGE_EXT.contains(extension(file.getName().toLowerCase(Locale.US)));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return "";
        return name.substring(dot + 1);
    }

    private static byte[] read(File file, int size) throws IOException {
        byte[] data = new byte[size];
        FileInputStream in = new FileInputStream(file);
        try {
            int off = 0;
            while (off < size) {
                int n = in.read(data, off, size - off);
                if (n < 0) break;
                off += n;
            }
            if (off < size) return Arrays.copyOf(data, off);
            return data;
        } finally {
            in.close();
        }
    }

    private static void writeSlice(File out, byte[] data, int offset, int len) throws IOException {
        FileOutputStream stream = new FileOutputStream(out);
        try {
            stream.write(data, offset, len);
        } finally {
            stream.close();
        }
    }

    private static String reason(String path, Carve piece) {
        if (!piece.complete) return piece.offset > 0 ? "carved" : "truncated";
        if (piece.offset > 0) return "carved";
        String base = baseName(path).toLowerCase(Locale.US);
        String lower = path.toLowerCase(Locale.US);
        if (base.startsWith(".trashed-")) return "trashed";
        if (base.startsWith(".pending-")) return "pending";
        if (lower.contains("lost.dir")) return "lostdir";
        if (lower.contains(".thumbnails") || base.startsWith(".thumb") || base.startsWith("thumb")) return "thumbnail";
        if (base.startsWith(".")) return "hidden";
        String ext = extension(base);
        if (!matchesExt(piece.format, ext)) return "badext";
        return "intact";
    }

    private static boolean matchesExt(String format, String ext) {
        if ("jpeg".equals(format)) return "jpg".equals(ext) || "jpeg".equals(ext);
        if ("heic".equals(format)) return "heic".equals(ext) || "heif".equals(ext);
        return format.equals(ext);
    }

    private static String label(String reason) {
        if ("trashed".equals(reason)) return "سلة الحذف";
        if ("pending".equals(reason)) return "حفظ ناقص";
        if ("hidden".equals(reason)) return "ملف مخفي";
        if ("lostdir".equals(reason)) return "LOST.DIR";
        if ("badext".equals(reason)) return "امتداد ضايع";
        if ("carved".equals(reason)) return "منحوت";
        if ("truncated".equals(reason)) return "مبتور";
        if ("thumbnail".equals(reason)) return "مصغّرة";
        return reason;
    }

    private static int score(String reason, boolean complete) {
        if (!complete && "truncated".equals(reason)) return 31;
        int s = 90;
        if (!complete) s -= 22;
        if ("carved".equals(reason)) s -= 5;
        if ("trashed".equals(reason) || "lostdir".equals(reason)) s += 4;
        if ("thumbnail".equals(reason)) s -= 7;
        return Math.max(15, Math.min(97, s));
    }

    private static String suggest(String path, String format, int index) {
        String name = baseName(path);
        if (name.startsWith(".trashed-")) {
            int cut = name.indexOf('-', 9);
            if (cut > 0 && cut + 1 < name.length()) name = name.substring(cut + 1);
        }
        if (name.startsWith(".pending-")) {
            int cut = name.indexOf('-', 9);
            if (cut > 0 && cut + 1 < name.length()) name = name.substring(cut + 1);
        }
        if (name.startsWith(".")) name = name.substring(1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.length() == 0) name = "photo-" + index;
        return name + "." + extOf(format);
    }

    private static String extOf(String format) {
        if ("jpeg".equals(format)) return "jpg";
        return format;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String kb(int len) {
        if (len < 1024) return len + " ب";
        if (len < 1024 * 1024) return Math.max(1, len / 1024) + " ك.ب";
        return String.format(Locale.US, "%.1f م.ب", len / (1024.0 * 1024.0));
    }

    static final class Carve {
        final String format;
        final int offset;
        final int end;
        final boolean complete;

        Carve(String format, int offset, int end, boolean complete) {
            this.format = format;
            this.offset = offset;
            this.end = end;
            this.complete = complete;
        }
    }

    static List<Carve> carve(byte[] data, boolean deep) {
        List<Carve> hits = new ArrayList<Carve>();
        add(hits, sniff(data, 0));
        if (deep) {
            for (int i = 1; i < data.length - 12 && hits.size() < 4; i++) {
                Carve cover = covering(hits, i);
                if (cover != null) {
                    i = cover.end - 1;
                    continue;
                }
                int b0 = data[i] & 0xff;
                if (b0 == 0xff && (data[i + 1] & 0xff) == 0xd8 && (data[i + 2] & 0xff) == 0xff) {
                    add(hits, parseJpeg(data, i));
                } else if (b0 == 0x89 && (data[i + 1] & 0xff) == 0x50 && (data[i + 2] & 0xff) == 0x4e) {
                    add(hits, parsePng(data, i));
                } else if (b0 == 0x52 && (data[i + 1] & 0xff) == 0x49 && (data[i + 2] & 0xff) == 0x46) {
                    add(hits, parseWebp(data, i));
                }
            }
        }
        return hits;
    }

    private static Carve covering(List<Carve> hits, int index) {
        for (Carve hit : hits) if (index >= hit.offset && index < hit.end) return hit;
        return null;
    }

    private static void add(List<Carve> hits, Carve hit) {
        if (hit == null || hit.end <= hit.offset) return;
        if (!hit.complete && hit.end - hit.offset < 8192) return;
        for (Carve old : hits) {
            if (hit.offset < old.end && hit.end > old.offset) return;
        }
        hits.add(hit);
    }

    private static Carve sniff(byte[] data, int offset) {
        Carve jpeg = parseJpeg(data, offset);
        if (jpeg != null) return jpeg;
        Carve png = parsePng(data, offset);
        if (png != null) return png;
        Carve webp = parseWebp(data, offset);
        if (webp != null) return webp;
        if (offset != 0) return null;
        return parseIso(data);
    }

    private static Carve parseJpeg(byte[] data, int offset) {
        if (offset + 4 > data.length) return null;
        if ((data[offset] & 0xff) != 0xff || (data[offset + 1] & 0xff) != 0xd8 || (data[offset + 2] & 0xff) != 0xff)
            return null;
        int i = offset + 2;
        boolean sawSos = false;
        while (i < data.length) {
            if ((data[i] & 0xff) != 0xff) return sawSos ? new Carve("jpeg", offset, data.length, false) : null;
            while (i < data.length && (data[i] & 0xff) == 0xff) i++;
            if (i >= data.length) return new Carve("jpeg", offset, data.length, false);
            int marker = data[i++] & 0xff;
            if (marker == 0xd9) return new Carve("jpeg", offset, i, true);
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (i + 1 >= data.length) return new Carve("jpeg", offset, data.length, false);
            int len = ((data[i] & 0xff) << 8) | (data[i + 1] & 0xff);
            if (len < 2) return null;
            if (i + len > data.length) return sawSos ? new Carve("jpeg", offset, data.length, false) : null;
            if (marker == 0xda) {
                sawSos = true;
                i += len;
                while (i < data.length) {
                    if ((data[i] & 0xff) != 0xff) {
                        i++;
                        continue;
                    }
                    int j = i + 1;
                    while (j < data.length && (data[j] & 0xff) == 0xff) j++;
                    if (j >= data.length) return new Carve("jpeg", offset, data.length, false);
                    int next = data[j] & 0xff;
                    if (next == 0x00 || (next >= 0xd0 && next <= 0xd7)) {
                        i = j + 1;
                        continue;
                    }
                    i = j - 1;
                    break;
                }
                if (i >= data.length) return new Carve("jpeg", offset, data.length, false);
                continue;
            }
            i += len;
        }
        return sawSos ? new Carve("jpeg", offset, data.length, false) : null;
    }

    private static Carve parsePng(byte[] data, int offset) {
        int[] sig = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (offset + 8 > data.length) return null;
        for (int k = 0; k < 8; k++) if ((data[offset + k] & 0xff) != sig[k]) return null;
        int i = offset + 8;
        while (i + 8 <= data.length) {
            int len = ((data[i] & 0xff) << 24) | ((data[i + 1] & 0xff) << 16) | ((data[i + 2] & 0xff) << 8) | (data[i + 3] & 0xff);
            if (len < 0 || len > 24_000_000) return null;
            String type = new String(data, i + 4, 4);
            int next = i + 12 + len;
            if (next > data.length) return new Carve("png", offset, data.length, false);
            i = next;
            if ("IEND".equals(type)) return new Carve("png", offset, i, true);
        }
        return i > offset + 8 ? new Carve("png", offset, data.length, false) : null;
    }

    private static Carve parseWebp(byte[] data, int offset) {
        if (offset + 12 > data.length) return null;
        if ((data[offset] & 0xff) != 0x52 || (data[offset + 1] & 0xff) != 0x49) return null;
        if ((data[offset + 8] & 0xff) != 0x57 || (data[offset + 9] & 0xff) != 0x45) return null;
        int size = (data[offset + 4] & 0xff) | ((data[offset + 5] & 0xff) << 8)
                | ((data[offset + 6] & 0xff) << 16) | ((data[offset + 7] & 0xff) << 24);
        int total = 8 + size;
        if (total < 12) return null;
        int end = offset + total;
        if (end > data.length) return new Carve("webp", offset, data.length, false);
        return new Carve("webp", offset, end, true);
    }

    private static Carve parseIso(byte[] data) {
        if (data.length < 12) return null;
        if ((data[4] & 0xff) != 0x66 || (data[5] & 0xff) != 0x74 || (data[6] & 0xff) != 0x79 || (data[7] & 0xff) != 0x70)
            return null;
        String brand = new String(data, 8, 4).toLowerCase(Locale.US);
        if ("heic".equals(brand) || "heix".equals(brand) || "heif".equals(brand) || "mif1".equals(brand))
            return new Carve("heic", 0, data.length, true);
        if ("avif".equals(brand) || "avis".equals(brand)) return new Carve("avif", 0, data.length, true);
        return null;
    }
}
