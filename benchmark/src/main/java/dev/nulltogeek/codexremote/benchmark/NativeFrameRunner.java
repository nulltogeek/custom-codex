package dev.nulltogeek.codexremote.benchmark;

import android.app.*;
import android.content.*;
import android.content.SharedPreferences;
import android.os.*;
import android.security.keystore.*;
import android.util.Base64;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import org.json.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.*;

/** Android-only instrumentation: no dependency on the target's optimized libraries. */
public final class NativeFrameRunner extends Instrumentation {
    private Bundle arguments;
    private volatile Activity activity;
    private final List<Long> frames = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong droppedReports = new AtomicLong();
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); HandlerThread thread = new HandlerThread("native-frame-metrics");
        boolean fixtureAllowed = "true".equals(arguments.getString("fixture")) && Build.PRODUCT.contains("sdk");
        thread.start();
        Application application = (Application) getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {
                activity = a;
                a.getWindow().addOnFrameMetricsAvailableListener((window, metrics, dropped) -> {
                    frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)); droppedReports.addAndGet(dropped);
                }, new Handler(thread.getLooper()));
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        try {
            if (!fixtureAllowed)
                throw new IllegalStateException("Use -e fixture true only on a disposable Android emulator");
            seedFixture();
            runOnMainSync(() -> application.registerActivityLifecycleCallbacks(lifecycle));
            Intent launch = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext().getPackageName(),
                "dev.nulltogeek.codexremote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(launch);
            long until = SystemClock.uptimeMillis() + 30000;
            while (activity == null && SystemClock.uptimeMillis() < until) Thread.sleep(100);
            if (activity == null) throw new IllegalStateException("Target activity did not start");
            Thread.sleep(5000);
            clickNode(getUiAutomation().getRootInActiveWindow(), "Wait"); Thread.sleep(500);
            screenshot("release-dark-chats"); frames.clear(); droppedReports.set(0);
            for (int i = 0; i < 8; i++) swipe(true);
            for (int i = 0; i < 8; i++) swipe(false);
            boolean conversation = clickText("Benchmark chat 1");
            for (int i = 0; !conversation && i < 12; i++) { swipe(false); conversation = clickText("Benchmark chat 1"); }
            Thread.sleep(2000);
            if (conversation) screenshot("release-dark-conversation");
            boolean diff = conversation && clickText("app/MainActivity.kt");
            if (diff) {
                screenshot("release-dark-diff");
                for (int i = 0; i < 8; i++) swipe(true);
                clickText("Back"); Thread.sleep(1000);
            }
            if (conversation) for (int i = 0; i < 8; i++) swipe(false);
            Thread.sleep(500);
            float refresh = activity.getDisplay().getRefreshRate();
            long budget = (long) (1_000_000_000d / refresh);
            List<Long> measured; synchronized (frames) { measured = new ArrayList<>(frames); }
            long inside = measured.stream().filter(n -> n <= budget).count();
            JSONObject report = new JSONObject().put("buildType", "release").put("frames", measured.size())
                .put("refreshHz", refresh).put("frameBudgetNanos", budget)
                .put("withinBudgetPercent", measured.isEmpty() ? 0d : 100d * inside / measured.size())
                .put("targetPercent", 95).put("device", Build.MODEL)
                .put("droppedMetricReports", droppedReports.get())
                .put("widthPixels", getTargetContext().getResources().getDisplayMetrics().widthPixels)
                .put("heightPixels", getTargetContext().getResources().getDisplayMetrics().heightPixels)
                .put("cachedConversationOpened", conversation).put("largeDiffOpened", diff)
                .put("scrollingMeasured", true).put("streamingMeasured", false)
                .put("harness", "Android platform instrumentation on the minified release APK");
            Files.write(new File(getTargetContext().getExternalFilesDir(null), "release-frame-metrics.json").toPath(),
                report.toString(2).getBytes(StandardCharsets.UTF_8));
            result.putString("stream", report.toString(2) + "\n");
            if (measured.isEmpty() || !conversation || !diff) throw new IllegalStateException("Incomplete benchmark: " + report + " visible UI: " + visibleText(getUiAutomation().getRootInActiveWindow()));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", error.toString() + "\n"); finish(Activity.RESULT_CANCELED, result);
        } finally {
            runOnMainSync(() -> application.unregisterActivityLifecycleCallbacks(lifecycle));
            thread.quitSafely();
            if (fixtureAllowed) getTargetContext().getSharedPreferences("codex_remote", Context.MODE_PRIVATE).edit().remove("token").remove("endpoint").commit();
        }
    }
    private void seedFixture() throws Exception {
        Context context = getTargetContext();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        SecretKey key = (SecretKey) keys.getKey("codex_remote_pairing", null);
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("codex_remote_pairing", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        String token = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal("disposable-benchmark-fixture-credential".getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        context.getSharedPreferences("codex_remote", Context.MODE_PRIVATE).edit()
            .putString("endpoint", "https://benchmark.invalid").putString("token", token).putString("theme", "dark").commit();
        JSONArray chats = new JSONArray(), projects = new JSONArray(), items = new JSONArray();
        for (int i = 1; i <= 100; i++) chats.put(new JSONObject().put("id", "bench" + i).put("name", "Benchmark chat " + i)
            .put("preview", "Review native Android changes and verify rendering").put("cwd", "/benchmark")
            .put("status", new JSONObject().put("type", "idle")));
        for (int i = 1; i <= 15; i++) projects.put(new JSONObject().put("id", "p" + i).put("name", "Project " + i)
            .put("rootPaths", new JSONArray().put("/benchmark")));
        for (int i = 1; i <= 100; i++) {
            StringBuilder message = new StringBuilder();
            for (int j = 0; j < 30; j++) message.append("Message ").append(i).append(": native cached conversation output.\n");
            items.put(new JSONObject().put("id", "i" + i).put("type", "agentMessage").put("text", message.toString()));
        }
        StringBuilder lines = new StringBuilder(); for (int i = 0; i < 1200; i++) lines.append("+updated code line ").append(i).append('\n');
        items.put(new JSONObject().put("id", "diff").put("type", "fileChange").put("status", "completed")
            .put("changes", new JSONArray().put(new JSONObject().put("path", "app/MainActivity.kt").put("diff", lines.toString()))));
        JSONObject history = new JSONObject().put("id", "bench1").put("turns", new JSONArray().put(new JSONObject()
            .put("id", "turn").put("status", "completed").put("items", items)));
        JSONObject cache = new JSONObject().put("chats", chats).put("threads", new JSONObject().put("bench1", history))
            .put("inventory", new JSONObject().put("projects", projects).put("assignments", new JSONObject()));
        Files.write(new File(context.getFilesDir(), "history.json").toPath(), cache.toString().getBytes(StandardCharsets.UTF_8));
    }
    private boolean clickText(String text) throws Exception {
        for (int i = 0; i < 20; i++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (clickNode(root, text)) return true;
            Thread.sleep(250);
        }
        return false;
    }
    private boolean clickNode(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        String label = String.valueOf(node.getText()), description = String.valueOf(node.getContentDescription());
        if (label.equals(text) || label.startsWith(text + "\n") || description.equals(text)) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                long now = SystemClock.uptimeMillis();
                injectTouch(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0));
                injectTouch(MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0));
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) if (clickNode(node.getChild(i), text)) return true;
        return false;
    }
    private String visibleText(AccessibilityNodeInfo node) {
        if (node == null) return "No accessible window";
        StringBuilder text = new StringBuilder();
        if (node.getText() != null) text.append(node.getText()).append(" | ");
        for (int i = 0; i < node.getChildCount() && text.length() < 2000; i++) text.append(visibleText(node.getChild(i)));
        return text.toString();
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap != null) try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle();
        }
    }
    private void injectTouch(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!getUiAutomation().injectInputEvent(event, true)) throw new IllegalStateException("Touch injection failed");
        } finally { event.recycle(); }
    }
    private void swipe(boolean up) throws Exception {
        int width = getTargetContext().getResources().getDisplayMetrics().widthPixels;
        int height = getTargetContext().getResources().getDisplayMetrics().heightPixels;
        float from = height * (up ? 0.72f : 0.35f), to = height * (up ? 0.35f : 0.72f);
        long start = SystemClock.uptimeMillis();
        injectTouch(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, width * 0.5f, from, 0));
        for (int i = 1; i <= 20; i++) {
            Thread.sleep(20);
            injectTouch(MotionEvent.obtain(start, SystemClock.uptimeMillis(), i == 20 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                width * 0.5f, from + (to - from) * i / 20f, 0));
        }
        Thread.sleep(100);
    }
}
