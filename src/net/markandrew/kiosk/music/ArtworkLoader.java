package net.markandrew.kiosk.music;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** In-memory, downsampled artwork; cookies go only to this session's MA proxy. External radio art is HTTPS without credentials or redirects. */
final class ArtworkLoader {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(64), runnable -> {
            Thread thread = new Thread(runnable, "musicassistantplugin-artwork"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    void load(IngressAccess.Session session, String path, BooleanSupplier current, Consumer<Bitmap> done) {
        if (path.isEmpty() || !current.getAsBoolean()) return;
        worker.execute(() -> {
            if (!current.getAsBoolean()) return;
            String key = session.userId + "|" + session.base + "|" + path;
            Bitmap cached = cache.get(key);
            if (cached != null) { if (current.getAsBoolean()) done.accept(cached); return; }
            HttpURLConnection connection = null;
            try {
                boolean external=path.startsWith("https://");
                URI url = external?new URI(path):session.base.resolve(path);
                if(external){if(!path.equals(RadioMetadata.image(path)))return;}
                else if (!url.toString().startsWith(session.base.toString() + "imageproxy")
                    || !url.getRawPath().equals(session.base.getRawPath() + path.split("\\?", 2)[0])) return;
                connection = (HttpURLConnection) url.toURL().openConnection();
                active.set(connection);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                if(!external)connection.setRequestProperty("Cookie", session.cookie);
                if (connection.getResponseCode() != 200) return;
                String type = connection.getContentType();
                if (type == null || !type.startsWith("image/") || connection.getContentLengthLong() > 512 * 1024) return;
                byte[] bytes;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (!current.getAsBoolean() || output.size() + count > 512 * 1024) return;
                        output.write(buffer, 0, count);
                    }
                    bytes = output.toByteArray();
                }
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096) return;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap != null && current.getAsBoolean()) { cache.put(key, bitmap); done.accept(bitmap); }
            } catch (Exception ignored) { /* Keep the themed placeholder; never log image URLs. */ }
            finally { active.compareAndSet(connection, null); if (connection != null) connection.disconnect(); }
        });
    }

    void clear() {
        cancelPending();
        cache.evictAll();
    }
    void cancelPending() {
        worker.getQueue().clear();
        HttpURLConnection connection = active.getAndSet(null);
        if (connection != null) connection.disconnect();
    }
    void stop() { clear(); worker.shutdownNow(); }
}
