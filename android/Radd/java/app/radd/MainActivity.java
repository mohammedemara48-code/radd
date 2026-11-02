package app.radd;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#121614");
    private static final int SURFACE = Color.parseColor("#1B2320");
    private static final int FG = Color.parseColor("#E8F1EB");
    private static final int MUTED = Color.parseColor("#93A399");
    private static final int ACCENT = Color.parseColor("#E0A15A");

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final boolean[] stop = new boolean[] {false};
    private TextView status;
    private LinearLayout results;
    private Button scanButton;
    private Button saveButton;
    private List<Scanner.Hit> hits = new ArrayList<Scanner.Hit>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(16);
        root.setPadding(pad, dp(20), pad, pad);

        TextView title = text("ردّ", 28, FG, true);
        TextView sub = text("فحص الذاكرة على الجهاز. مفيش إنترنت ومفيش رفع.", 14, MUTED, false);
        status = text("محتاج إذن كل الملفات عشان يقرأ DCIM و Download من غير نافذة المتصفح.", 14, FG, false);

        Button grant = button("السماح بقراءة الذاكرة", false);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAllFilesAccess();
            }
        });
        scanButton = button("فحص الذاكرة", true);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        saveButton = button("حفظ المسترجع في RaddRecovered", false);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveHits();
            }
        });

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(title);
        root.addView(gap(6));
        root.addView(sub);
        root.addView(gap(14));
        root.addView(status);
        root.addView(gap(12));
        root.addView(grant);
        root.addView(gap(8));
        root.addView(scanButton);
        root.addView(gap(8));
        root.addView(saveButton);
        root.addView(gap(12));
        root.addView(scroll);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = canReadStorage();
        scanButton.setEnabled(ok);
        if (!ok) status.setText("من الإعدادات فعّل «السماح بإدارة كل الملفات» لتطبيق ردّ، وبعدين ارجع.");
        else if (hits.isEmpty()) status.setText("الإذن جاهز. الفحص بيحصل هنا على الموبايل، والصور مش بتطلع بره.");
    }

    @Override
    protected void onDestroy() {
        stop[0] = true;
        pool.shutdownNow();
        super.onDestroy();
    }

    private boolean canReadStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return true;
    }

    private void openAllFilesAccess() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ex) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void startScan() {
        if (!canReadStorage()) {
            openAllFilesAccess();
            return;
        }
        stop[0] = false;
        hits = new ArrayList<Scanner.Hit>();
        results.removeAllViews();
        saveButton.setEnabled(false);
        scanButton.setEnabled(false);
        status.setText("بيفحص التخزين الداخلي… سيب التطبيق مفتوح.");
        final File root = Environment.getExternalStorageDirectory();
        final File cache = new File(getCacheDir(), "hits");
        if (cache.exists()) deleteDir(cache);
        cache.mkdirs();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Scanner scanner = new Scanner();
                final List<Scanner.Hit> found = scanner.scan(root, cache, true, new Scanner.Progress() {
                    @Override
                    public void onProgress(final int seen, final int count, final String path) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("اتفحص " + seen + " ملف · لقطات " + count);
                            }
                        });
                    }
                }, stop);
                final int intact = scanner.skippedIntact;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hits = found;
                        scanButton.setEnabled(true);
                        saveButton.setEnabled(!found.isEmpty());
                        status.setText(found.size() + " لقطة قابلة للاسترجاع. الصور السليمة الظاهرة اتخطت (" + intact + ").");
                        showHits(found);
                    }
                });
            }
        });
    }

    private void showHits(List<Scanner.Hit> found) {
        results.removeAllViews();
        if (found.isEmpty()) {
            results.addView(text("مفيش ملفات محذوفة الاسم أو منحوتة. القطاعات اللي الفلاش مسحها مش بتتقرأ من غير روت.", 14, MUTED, false));
            return;
        }
        for (Scanner.Hit hit : found) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(card());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            ImageView image = new ImageView(this);
            image.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(72)));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = thumb(hit.cacheFile);
            if (thumb != null) image.setImageBitmap(thumb);
            else image.setBackgroundColor(Color.parseColor("#24302B"));

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            meta.setPadding(dp(10), 0, 0, 0);
            meta.addView(text(hit.restoreName, 14, FG, true));
            meta.addView(text(hit.reason + " · " + hit.confidence + "% · " + hit.detail, 12, ACCENT, false));
            meta.addView(text(hit.sourcePath, 11, MUTED, false));
            row.addView(image);
            row.addView(meta);
            results.addView(row);
        }
    }

    private void saveHits() {
        File dir = new File(Environment.getExternalStorageDirectory(), "RaddRecovered");
        dir.mkdirs();
        int saved = 0;
        for (Scanner.Hit hit : hits) {
            File dest = unique(new File(dir, hit.restoreName));
            try {
                copy(hit.cacheFile, dest);
                saved++;
            } catch (IOException ignored) {
            }
        }
        status.setText("اتحفظ " + saved + " ملف في الذاكرة الداخلية / RaddRecovered");
    }

    private static File unique(File file) {
        if (!file.exists()) return file;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 500; i++) {
            File next = new File(file.getParentFile(), stem + "-" + i + ext);
            if (!next.exists()) return next;
        }
        return file;
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    private static void deleteDir(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File kid : kids) kid.delete();
        dir.delete();
    }

    private Bitmap thumb(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? BG : FG);
        button.setBackground(pill(primary ? ACCENT : SURFACE));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(24));
        return shape;
    }

    private GradientDrawable card() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE);
        shape.setCornerRadius(dp(16));
        return shape;
    }

    private View gap(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
