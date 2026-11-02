import { useEffect, useMemo, useRef, useState } from "react";
import {
  Aperture,
  Check,
  ChevronLeft,
  ChevronRight,
  Download,
  FolderOpen,
  ImageOff,
  ShieldAlert,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { buildDemoSources } from "@/lib/recover/demo";
import {
  FORMAT_LABEL,
  MAX_BYTES,
  REASON_LABEL,
  revokeHits,
  scanSources,
  type Hit,
  type Progress,
  type Reason,
  type ScanSource,
} from "@/lib/recover/engine";
import { previewPathPrefix } from "@/lib/preview-path-prefix";
import { zipBlobs } from "@/lib/recover/zip";
import { cn } from "@/lib/cn";

type Filter = "recoverable" | "all" | Reason;

type Phase =
  | { name: "home" }
  | { name: "scan"; progress: Progress; kind: "demo" | "folder" }
  | {
      name: "results";
      hits: Hit[];
      scanned: number;
      skipped: number;
      cancelled: boolean;
      kind: "demo" | "folder";
      deep: boolean;
    };

const SAMPLES = [
  { src: "/demo/harbor.jpg", label: "سلة الحذف", alt: "ميناء هادئ وقت الغروب" },
  { src: "/demo/dunes.jpg", label: "LOST.DIR", alt: "كثبان رملية في آخر النهار" },
  { src: "/demo/pomegranate.jpg", label: "منحوت من كاش", alt: "رمان مفتوح على حجر داكن" },
  { src: "/demo/camera.jpg", label: "ملف مبتور", alt: "كاميرا فيلم قديمة على خشب" },
];

const FILTERS: { id: Filter; label: string }[] = [
  { id: "recoverable", label: "قابلة للاسترجاع" },
  { id: "all", label: "الكل" },
  { id: "trashed", label: "سلة الحذف" },
  { id: "carved", label: "منحوتة" },
  { id: "lostdir", label: "LOST.DIR" },
  { id: "truncated", label: "مبتورة" },
  { id: "thumbnail", label: "مصغّرات" },
];

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} ب`;
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} ك.ب`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} م.ب`;
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1500);
}

function matches(hit: Hit, filter: Filter): boolean {
  if (filter === "all") return true;
  if (filter === "recoverable") return hit.reason !== "intact";
  return hit.reason === filter;
}

export function RecoverApp() {
  const [deep, setDeep] = useState(true);
  const [phase, setPhase] = useState<Phase>({ name: "home" });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [filter, setFilter] = useState<Filter>("recoverable");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [activeId, setActiveId] = useState<string | null>(null);
  const [zipping, setZipping] = useState(false);
  const [assetBase, setAssetBase] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const hitsRef = useRef<Hit[]>([]);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    setAssetBase(previewPathPrefix(window.location.hostname, window.location.pathname) ?? "");
    const input = inputRef.current;
    input?.setAttribute("webkitdirectory", "");
    input?.setAttribute("directory", "");
    return () => {
      abortRef.current?.abort();
      revokeHits(hitsRef.current);
    };
  }, []);

  useEffect(() => {
    if (!activeId) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setActiveId(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [activeId]);

  const hits = phase.name === "results" ? phase.hits : [];
  const visible = useMemo(() => hits.filter((hit) => matches(hit, filter)), [hits, filter]);
  const activeIndex = visible.findIndex((hit) => hit.id === activeId);
  const active = activeIndex >= 0 ? visible[activeIndex] : null;

  function remember(next: Hit[]) {
    revokeHits(hitsRef.current);
    hitsRef.current = next;
  }

  function goHome() {
    abortRef.current?.abort();
    remember([]);
    setSelected(new Set());
    setActiveId(null);
    setPhase({ name: "home" });
  }

  async function runScan(sources: ScanSource[], kind: "demo" | "folder", deepScan: boolean) {
    setError("");
    setFilter("recoverable");
    setSelected(new Set());
    setActiveId(null);
    const controller = new AbortController();
    abortRef.current = controller;
    setPhase({
      name: "scan",
      kind,
      progress: { done: 0, total: sources.length, path: "تجهيز الفحص", found: 0 },
    });
    try {
      const result = await scanSources(sources, {
        deep: deepScan,
        signal: controller.signal,
        onProgress: (progress) => {
          setPhase({ name: "scan", kind, progress });
        },
      });
      remember(result.hits);
      setPhase({
        name: "results",
        hits: result.hits,
        scanned: result.scanned,
        skipped: result.skipped,
        cancelled: result.cancelled,
        kind,
        deep: deepScan,
      });
    } catch {
      remember([]);
      setError("الفحص وقف قبل ما يخلّص. جرّب المجلد تاني.");
      setPhase({ name: "home" });
    }
  }

  async function onDemo() {
    if (busy) return;
    setBusy(true);
    try {
      const sources = await buildDemoSources();
      await runScan(sources, "demo", deep);
    } catch {
      setError("عينات المعمل مش متاحة دلوقتي.");
      setPhase({ name: "home" });
    } finally {
      setBusy(false);
    }
  }

  async function onFiles(list: FileList | null) {
    if (!list || list.length === 0) return;
    const sources = Array.from(list).map((file) => ({
      file,
      path: file.webkitRelativePath || file.name,
    }));
    if (inputRef.current) inputRef.current.value = "";
    await runScan(sources, "folder", deep);
  }

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function selectVisible() {
    setSelected((current) => {
      const allOn = visible.length > 0 && visible.every((hit) => current.has(hit.id));
      if (allOn) return new Set();
      return new Set(visible.map((hit) => hit.id));
    });
  }

  async function downloadHits(list: Hit[]) {
    if (list.length === 0 || zipping) return;
    if (list.length === 1) {
      downloadBlob(list[0].blob, list[0].restoreName);
      return;
    }
    setZipping(true);
    try {
      const zip = await zipBlobs(list.map((hit) => ({ name: hit.restoreName, blob: hit.blob })));
      downloadBlob(zip, "radd-recovered.zip");
    } finally {
      setZipping(false);
    }
  }

  const selectedHits = visible.filter((hit) => selected.has(hit.id));
  const downloadList = selectedHits.length > 0 ? selectedHits : visible;

  return (
    <main className="min-h-screen overflow-x-hidden bg-bg text-fg">
      <header className="sticky top-0 z-20 border-b border-line border-t-2 border-t-accent bg-bg/90 backdrop-blur">
        <div className="mx-auto flex w-full max-w-5xl items-center justify-between gap-3 px-4 py-3">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-accent text-bg">
              <Aperture className="size-5" aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <p className="truncate text-base font-semibold leading-tight">ردّ</p>
              <p className="truncate text-xs text-muted">استرجاع الصور بالبصمة</p>
            </div>
          </div>
          {phase.name !== "home" ? (
            <Button variant="quiet" onClick={goHome}>
              <ChevronRight className="size-4" aria-hidden="true" />
              البداية
            </Button>
          ) : null}
        </div>
      </header>

      <div className="mx-auto w-full max-w-5xl px-4 py-6">
        {error ? (
          <p className="mb-4 rounded-2xl border border-line bg-surface px-4 py-3 text-sm text-fg" role="alert">
            {error}
          </p>
        ) : null}

        {phase.name === "home" ? (
          <HomeView
            assetBase={assetBase}
            deep={deep}
            busy={busy}
            onDeep={setDeep}
            onDemo={() => void onDemo()}
            onPick={() => inputRef.current?.click()}
          />
        ) : null}

        {phase.name === "scan" ? <ScanView progress={phase.progress} onCancel={goHome} /> : null}

        {phase.name === "results" ? (
          <ResultsView
            phase={phase}
            filter={filter}
            visible={visible}
            selected={selected}
            zipping={zipping}
            downloadCount={downloadList.length}
            onFilter={setFilter}
            onToggle={toggle}
            onOpen={setActiveId}
            onSelectVisible={selectVisible}
            onDownload={() => void downloadHits(downloadList)}
          />
        ) : null}
      </div>

      <input
        ref={inputRef}
        className="sr-only"
        type="file"
        multiple
        onChange={(event) => void onFiles(event.target.files)}
      />

      {active ? (
        <Lightbox
          hit={active}
          index={activeIndex}
          total={visible.length}
          onClose={() => setActiveId(null)}
          onPrev={() => {
            const prev = visible[activeIndex - 1];
            if (prev) setActiveId(prev.id);
          }}
          onNext={() => {
            const next = visible[activeIndex + 1];
            if (next) setActiveId(next.id);
          }}
          onDownload={() => downloadBlob(active.blob, active.restoreName)}
        />
      ) : null}
    </main>
  );
}

function HomeView({
  assetBase,
  deep,
  busy,
  onDeep,
  onDemo,
  onPick,
}: {
  assetBase: string;
  deep: boolean;
  busy: boolean;
  onDeep: (value: boolean) => void;
  onDemo: () => void;
  onPick: () => void;
}) {
  return (
    <div className="grid gap-8 md:grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] md:items-start">
      <section>
        <p className="text-sm font-medium text-accent">تطبيق أوفلاين على الموبايل · من غير رفع للموقع</p>
        <h1 className="mt-2 max-w-xl text-3xl font-semibold leading-tight md:text-5xl">الفحص يتم على الذاكرة، مش على الموقع</h1>
        <p className="mt-4 max-w-xl text-base leading-relaxed text-muted">
          رسالة «هل تريد تحميل الملفات إلى هذا الموقع؟» من المتصفح. متوافقش عليها. التطبيق بيتنزّل مرة واحدة،
          وبعدين يشتغل من غير نت ويقرأ DCIM و Download بنفسه بعد إذن «كل الملفات».
        </p>

        <div className="mt-6 flex flex-col gap-3 sm:flex-row">
          <a
            href="https://github.com/mohammedemara48-code/radd/releases/latest"
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-h-11 items-center justify-center gap-2 rounded-full bg-accent px-5 text-sm font-medium text-bg"
          >
            <Download className="size-4" aria-hidden="true" />
            تنزيل تطبيق أوفلاين
          </a>
          <Button variant="ghost" onClick={onDemo} disabled={busy}>
            {busy ? <span className="size-4 animate-spin rounded-full border-2 border-fg/30 border-t-fg" /> : null}
            جرّب المعمل هنا
          </Button>
        </div>

        <div className="mt-5 flex items-center justify-between gap-4 rounded-2xl border border-line bg-surface px-4 py-3">
          <div>
            <p className="text-sm font-medium">فحص عميق</p>
            <p className="text-xs leading-relaxed text-muted">نحت الصورة حتى لو متدفّنة جوه ملف تاني</p>
          </div>
          <button
            type="button"
            aria-pressed={deep}
            onClick={() => onDeep(!deep)}
            className={cn(
              "flex h-7 w-12 shrink-0 items-center rounded-full p-0.5",
              deep ? "justify-end bg-accent" : "justify-start border border-line bg-raised",
            )}
          >
            <span className={cn("size-6 rounded-full", deep ? "bg-bg" : "bg-muted")} />
          </button>
        </div>

        <ul className="mt-6 divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
          {[
            ["البصمة", "JPG · PNG · WEBP · GIF · BMP · HEIC"],
            ["أنماط أندرويد", ".trashed- · .pending- · LOST.DIR · مصغّرات"],
            ["الحد", `حتى ${MAX_BYTES / (1024 * 1024)} م.ب للملف · التصدير ZIP`],
          ].map(([title, detail]) => (
            <li key={title} className="flex flex-col gap-1 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
              <span className="text-sm text-muted">{title}</span>
              <span className="text-sm font-medium sm:text-left">{detail}</span>
            </li>
          ))}
        </ul>
      </section>

      <aside className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-2">
          {SAMPLES.map((sample) => (
            <figure key={sample.src} className="relative overflow-hidden rounded-2xl border border-line bg-surface">
              <img
                src={`${assetBase}${sample.src}`}
                alt={sample.alt}
                className="aspect-video w-full object-cover"
              />
              <figcaption className="absolute inset-x-0 bottom-0 bg-bg/80 px-2 py-1.5 text-xs font-medium">
                {sample.label}
              </figcaption>
            </figure>
          ))}
        </div>

        <div className="rounded-2xl border border-line bg-surface p-4">
          <div className="flex items-start gap-3">
            <ShieldAlert className="mt-0.5 size-5 shrink-0 text-accent" aria-hidden="true" />
            <div className="space-y-2 text-sm leading-relaxed text-muted">
              <p className="font-medium text-fg">ليه نافذة المجلد رفضت Download؟</p>
              <p>
                أندرويد قافل مجلد Download من نافذة الاختيار «لحماية خصوصيتك». التطبيق مش بيستخدم النافذة دي.
                بعد التثبيت: السماح بقراءة الذاكرة، ثم فحص الذاكرة. النسخ بتتحفظ في مجلد RaddRecovered على الموبايل.
              </p>
              <p>
                اللي لسه مستحيل من غير روت: قراءة القطاعات اللي الفلاش مسحها خالص. التطبيق بيلاقي اللي لسه له أثر
                على التخزين: سلة الحذف، LOST.DIR، الملفات المخفية، والصور المدفونة جوه الكاش.
              </p>
            </div>
          </div>
        </div>
      </aside>
    </div>
  );
}

function ScanView({ progress, onCancel }: { progress: Progress; onCancel: () => void }) {
  const pct = progress.total === 0 ? 0 : Math.min(100, Math.round((progress.done / progress.total) * 100));
  return (
    <section className="mx-auto max-w-lg py-10" aria-live="polite">
      <p className="text-sm font-medium text-accent">جاري الفحص</p>
      <h2 className="mt-2 text-3xl font-semibold leading-tight">{pct}%</h2>
      <div
        className="mt-4 h-1.5 overflow-hidden rounded-full bg-surface"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
      >
        <div className="h-full bg-accent transition-[width] duration-200" style={{ width: `${pct}%` }} />
      </div>
      <dl className="mt-6 grid grid-cols-2 gap-3">
        <div className="rounded-2xl border border-line bg-surface px-4 py-3">
          <dt className="text-xs text-muted">الملفات</dt>
          <dd className="mt-1 text-lg font-semibold">
            {progress.done}
            <span className="text-sm font-normal text-muted"> / {progress.total}</span>
          </dd>
        </div>
        <div className="rounded-2xl border border-line bg-surface px-4 py-3">
          <dt className="text-xs text-muted">لقطات لحد دلوقتي</dt>
          <dd className="mt-1 text-lg font-semibold">{progress.found}</dd>
        </div>
      </dl>
      <p className="mt-4 truncate text-sm text-muted">{progress.path || "تجميع النتيجة"}</p>
      <Button variant="ghost" className="mt-6" onClick={onCancel}>
        إيقاف
      </Button>
    </section>
  );
}

function ResultsView({
  phase,
  filter,
  visible,
  selected,
  zipping,
  downloadCount,
  onFilter,
  onToggle,
  onOpen,
  onSelectVisible,
  onDownload,
}: {
  phase: Extract<Phase, { name: "results" }>;
  filter: Filter;
  visible: Hit[];
  selected: Set<string>;
  zipping: boolean;
  downloadCount: number;
  onFilter: (filter: Filter) => void;
  onToggle: (id: string) => void;
  onOpen: (id: string) => void;
  onSelectVisible: () => void;
  onDownload: () => void;
}) {
  const recoverable = phase.hits.filter((hit) => hit.reason !== "intact").length;
  const picked = visible.filter((hit) => selected.has(hit.id)).length;
  const allSelected = visible.length > 0 && visible.every((hit) => selected.has(hit.id));

  return (
    <section className="pb-24">
      <p className="text-sm font-medium text-accent">
        {phase.kind === "demo" ? "نتيجة المعمل — مش من موبايلك" : "نتيجة المجلد"}
      </p>
      <h2 className="mt-2 text-3xl font-semibold leading-tight">
        {recoverable === 0 ? "مفيش لقطات قابلة للاسترجاع" : `${recoverable} لقطة قابلة للاسترجاع`}
      </h2>
      <p className="mt-2 text-sm leading-relaxed text-muted">
        اتفحص {phase.scanned} ملف
        {phase.skipped > 0 ? ` · اتخطى ${phase.skipped} (فاضي أو أكبر من الحد)` : ""}
        {phase.cancelled ? " · الفحص اتوقف قبل آخر الملف" : ""}
        {!phase.deep ? " · الفحص السريع مش بينحت من جوه الملفات" : ""}
      </p>

      <div className="chiprow mt-5 flex gap-2 overflow-x-auto">
        {FILTERS.map((item) => {
          const count =
            item.id === "recoverable"
              ? recoverable
              : item.id === "all"
                ? phase.hits.length
                : phase.hits.filter((hit) => hit.reason === item.id).length;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onFilter(item.id)}
              className={cn(
                "min-h-11 shrink-0 rounded-full border px-3 text-sm",
                filter === item.id ? "border-accent bg-accent text-bg" : "border-line bg-surface text-fg",
              )}
            >
              {item.label}
              <span className="ms-1 opacity-70">{count}</span>
            </button>
          );
        })}
      </div>

      {visible.length === 0 ? (
        <div className="mt-8 rounded-2xl border border-line bg-surface px-4 py-10 text-center">
          <ImageOff className="mx-auto size-8 text-muted" aria-hidden="true" />
          <p className="mt-3 text-sm text-muted">مفيش حاجة في الفلتر ده. جرّب «الكل» أو مجلد DCIM.</p>
        </div>
      ) : (
        <ul className="mt-5 grid grid-cols-2 gap-3 md:grid-cols-3">
          {visible.map((hit) => (
            <li key={hit.id} className="min-w-0">
              <article
                className={cn(
                  "overflow-hidden rounded-2xl border bg-surface",
                  selected.has(hit.id) ? "border-accent" : "border-line",
                )}
              >
                <div className="relative">
                  <button type="button" className="block w-full" onClick={() => onOpen(hit.id)}>
                    {hit.previewUrl ? (
                      <img src={hit.previewUrl} alt="" className="aspect-video w-full object-cover" />
                    ) : (
                      <span className="flex aspect-video w-full flex-col items-center justify-center gap-2 bg-raised text-muted">
                        <ImageOff className="size-6" aria-hidden="true" />
                        <span className="text-xs">من غير معاينة</span>
                      </span>
                    )}
                  </button>
                  <button
                    type="button"
                    aria-pressed={selected.has(hit.id)}
                    aria-label={selected.has(hit.id) ? "إلغاء التحديد" : "تحديد"}
                    onClick={() => onToggle(hit.id)}
                    className={cn(
                      "absolute top-2 right-2 flex size-11 items-center justify-center rounded-full border",
                      selected.has(hit.id) ? "border-accent bg-accent text-bg" : "border-line bg-bg/80 text-fg",
                    )}
                  >
                    {selected.has(hit.id) ? <Check className="size-4" aria-hidden="true" /> : null}
                  </button>
                  <span className="absolute bottom-2 left-2 rounded-full bg-bg/85 px-2 py-1 text-xs font-medium">
                    {REASON_LABEL[hit.reason]}
                  </span>
                </div>
                <div className="space-y-1 px-3 py-3">
                  <p className="truncate text-sm font-medium">{hit.restoreName}</p>
                  <p className="truncate text-xs text-muted" title={hit.path}>
                    {hit.path}
                  </p>
                  <p className="text-xs text-muted">
                    {FORMAT_LABEL[hit.format]} · {formatBytes(hit.bytes)} · {hit.confidence}%
                    {hit.width > 0 ? ` · ${hit.width}×${hit.height}` : ""}
                    {hit.offset > 0 ? ` · من البايت ${hit.offset}` : ""}
                  </p>
                </div>
              </article>
            </li>
          ))}
        </ul>
      )}

      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-bg/95 px-4 py-3 backdrop-blur">
        <div className="mx-auto flex w-full max-w-5xl items-center justify-between gap-3">
          <Button variant="ghost" onClick={onSelectVisible} disabled={visible.length === 0}>
            {allSelected ? "إلغاء التحديد" : "تحديد الظاهر"}
          </Button>
          <Button onClick={onDownload} disabled={downloadCount === 0 || zipping}>
            <Download className="size-4" aria-hidden="true" />
            {zipping ? "تجهيز..." : picked > 0 ? `تنزيل ${downloadCount}` : `تنزيل الكل ${downloadCount}`}
          </Button>
        </div>
      </div>
    </section>
  );
}

function Lightbox({
  hit,
  index,
  total,
  onClose,
  onPrev,
  onNext,
  onDownload,
}: {
  hit: Hit;
  index: number;
  total: number;
  onClose: () => void;
  onPrev: () => void;
  onNext: () => void;
  onDownload: () => void;
}) {
  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-bg" role="dialog" aria-modal="true" aria-label={hit.restoreName}>
      <div className="flex items-center justify-between gap-2 px-3 py-3">
        <Button variant="quiet" size="icon" onClick={onClose} aria-label="إغلاق">
          <X className="size-5" />
        </Button>
        <p className="min-w-0 truncate text-sm text-muted">
          {index + 1} / {total}
        </p>
        <Button onClick={onDownload}>
          <Download className="size-4" aria-hidden="true" />
          تنزيل
        </Button>
      </div>
      <div className="flex min-h-0 flex-1 items-center justify-center px-4">
        {hit.previewUrl ? (
          <img src={hit.previewUrl} alt={hit.restoreName} className="max-h-full max-w-full object-contain" />
        ) : (
          <p className="text-sm text-muted">الملف مبتور أو صيغته مش مدعومة في المعاينة، بس تقدر تنزّله.</p>
        )}
      </div>
      <div className="flex items-center justify-between gap-3 px-3 py-4">
        <Button variant="ghost" size="icon" onClick={onPrev} disabled={index <= 0} aria-label="السابق">
          <ChevronRight className="size-5" />
        </Button>
        <div className="min-w-0 text-center">
          <p className="truncate text-sm font-medium">{hit.restoreName}</p>
          <p className="truncate text-xs text-muted">
            {REASON_LABEL[hit.reason]} · {hit.confidence}% · {hit.path}
          </p>
        </div>
        <Button variant="ghost" size="icon" onClick={onNext} disabled={index >= total - 1} aria-label="التالي">
          <ChevronLeft className="size-5" />
        </Button>
      </div>
    </div>
  );
}
