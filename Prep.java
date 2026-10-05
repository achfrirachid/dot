package com.tvlink.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Effects;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * تجهيز الفيديوهات للداتاشو (BenQ MP622c = 1024x768):
 * كيحول أي فيديو (VP9 / AV1 / 1080p ...) لـ MP4 H.264 + AAC بارتفاع 576 (1024x576) وبيتريت 2.5 Mbps.
 * التحويل كيتدار فالهاتف مرة وحدة (بلا إنترنت)، والنسخة الجاهزة كتبقى فالتطبيق.
 */
@UnstableApi
public class Prep {
    public static final int TARGET_H = 576;
    public static final int BITRATE = 2500000;

    public interface Listener {
        void onProgress(int index, int total, String name, int percent);
        void onItemDone(String name, boolean ok);
        void onAllDone(int okCount, int total);
    }

    public static class Job {
        public final Uri src;
        public final String name;
        public final File out;
        public Job(Uri src, String name, File out) { this.src = src; this.name = name; this.out = out; }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Job> jobs = new ArrayList<Job>();
    private final Listener cb;
    private Transformer current;
    private int idx = 0, okCount = 0;
    private boolean cancelled = false;

    public Prep(Context c, List<Job> jobs, Listener cb) {
        this.ctx = c.getApplicationContext();
        this.jobs.addAll(jobs);
        this.cb = cb;
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "dsh");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File outFor(Context c, String name, long size) {
        String base = name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9]", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        return new File(dir(c), base + "_" + size + ".mp4");
    }

    public void start() {
        main.post(new Runnable() { @Override public void run() { next(); } });
    }

    public void cancel() {
        cancelled = true;
        main.post(new Runnable() {
            @Override public void run() {
                try { if (current != null) current.cancel(); } catch (Exception ignored) {}
            }
        });
    }

    private void next() {
        if (cancelled || idx >= jobs.size()) {
            cb.onAllDone(okCount, jobs.size());
            return;
        }
        final Job j = jobs.get(idx);
        if (j.out.exists() && j.out.length() > 0) {
            okCount++;
            cb.onItemDone(j.name, true);
            idx++;
            next();
            return;
        }
        final File tmp = new File(j.out.getParentFile(), j.out.getName() + ".part");
        try { if (tmp.exists()) tmp.delete(); } catch (Exception ignored) {}
        try {
            int h = srcHeight(j.src);
            Effects fx = Effects.EMPTY;
            if (h > TARGET_H) {
                fx = new Effects(Collections.<androidx.media3.common.audio.AudioProcessor>emptyList(),
                        Collections.<androidx.media3.common.Effect>singletonList(Presentation.createForHeight(TARGET_H)));
            }
            EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(j.src)).setEffects(fx).build();
            DefaultEncoderFactory enc = new DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                    .build();
            current = new Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(enc)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) {
                            boolean ok = tmp.renameTo(j.out);
                            if (ok) okCount++;
                            cb.onItemDone(j.name, ok);
                            idx++;
                            next();
                        }
                        @Override public void onError(Composition c, ExportResult r, ExportException e) {
                            try { tmp.delete(); } catch (Exception ignored) {}
                            cb.onItemDone(j.name, false);
                            idx++;
                            next();
                        }
                    })
                    .build();
            current.start(item, tmp.getAbsolutePath());
            poll();
        } catch (Exception e) {
            cb.onItemDone(j.name, false);
            idx++;
            next();
        }
    }

    private void poll() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (current == null || cancelled) return;
                ProgressHolder ph = new ProgressHolder();
                int st = current.getProgress(ph);
                if (st == Transformer.PROGRESS_STATE_NOT_STARTED) return;
                if (idx < jobs.size()) cb.onProgress(idx + 1, jobs.size(), jobs.get(idx).name, ph.progress);
                poll();
            }
        }, 800);
    }

    private int srcHeight(Uri u) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, u);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if ("90".equals(rot) || "270".equals(rot)) return w;
            return h;
        } catch (Exception e) {
            return 9999;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }
}
