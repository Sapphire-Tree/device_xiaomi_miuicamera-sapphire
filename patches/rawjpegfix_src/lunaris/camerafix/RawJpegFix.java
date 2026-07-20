package lunaris.camerafix;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public class RawJpegFix {
    private static final String TAG = "RawJpegFix";
    private static final String PROCESSING_AUTHORITY =
            "com.xiaomi.camera.parallelservice.provider.SpecialTypesProvider";
    private static final long MAX_PLACEHOLDER_SIZE = 2L * 1024 * 1024;
    private static final long DEBOUNCE_MS = 2500;
    private static final AtomicLong lastAccepted = new AtomicLong(0);

    private static volatile boolean preloaded = false;

    public static void preload() {
        if (preloaded) {
            return;
        }
        preloaded = true;
        Thread t = new Thread(() -> {
            try {
                Class.forName("android.graphics.YuvImage");
                Class.forName("android.media.MediaScannerConnection");
                Class.forName("android.content.ContentValues");
                Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);

                int w = 64;
                int h = 64;
                byte[] nv21 = new byte[w * h * 3 / 2];
                for (int warm = 0; warm < 3; warm++) {
                    YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    yuv.compressToJpeg(new Rect(0, 0, w, h), 90, baos);
                    baos.toByteArray();
                }
                Log.i(TAG, "preload: warmup complete");
            } catch (Throwable t2) {
                Log.w(TAG, "preload: warmup failed (non-fatal)", t2);
            }
        }, "RawJpegFix-preload");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static void handle(Image image) {
        try {
            Log.i(TAG, "handle: ENTER format=" + image.getFormat() + " w=" + image.getWidth() + " h=" + image.getHeight());
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                Log.w(TAG, "handle: wrong format, skipping");
                return;
            }
            Context context = null;
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                context = (Context) app;
            } catch (Throwable ignored) {
            }
            if (context == null) {
                Log.w(TAG, "no context, skipping");
                return;
            }

            long now = System.currentTimeMillis();
            long prev = lastAccepted.get();
            if (now - prev < DEBOUNCE_MS || !lastAccepted.compareAndSet(prev, now)) {
                Log.i(TAG, "handle: debounced (another frame from the same burst already handling this capture)");
                return;
            }

            final Context ctx = context;
            Thread worker = new Thread(() -> encodeAndFinishAsync(ctx, image), "RawJpegFix-worker");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            Log.e(TAG, "handle failed", t);
        }
    }

    private static final class CamInfo {
        final int rotationDegrees;
        final boolean frontFacing;

        CamInfo(int rotationDegrees, boolean frontFacing) {
            this.rotationDegrees = rotationDegrees;
            this.frontFacing = frontFacing;
        }
    }

    private static void encodeAndFinishAsync(Context context, Image image) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        byte[] jpegBytes;
        int width;
        int height;
        try {
            int rawWidth = image.getWidth();
            int rawHeight = image.getHeight();
            byte[] nv21 = yuv420ToNv21(image);

            CamInfo camInfo = resolveCamInfo(context, rawWidth, rawHeight);
            width = rawWidth;
            height = rawHeight;
            for (int i = 0; i < camInfo.rotationDegrees / 90; i++) {
                nv21 = rotateNv21_90CW(nv21, width, height);
                int tmp = width;
                width = height;
                height = tmp;
            }

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 95, out);
            jpegBytes = out.toByteArray();

            String lutName = getActiveLutName();
            boolean needsLut = lutName != null;
            boolean needsUpscale = camInfo.frontFacing;

            if (needsLut || needsUpscale) {
                Bitmap decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                if (decoded != null) {
                    Bitmap current = decoded;
                    boolean changed = false;

                    if (needsLut) {
                        Bitmap lut = loadLutBitmap(context, lutName);
                        if (lut != null) {
                            Bitmap filtered = applyColorLookup(current, lut);
                            lut.recycle();
                            current.recycle();
                            current = filtered;
                            changed = true;
                            Log.i(TAG, "encodeAndFinishAsync: applied color filter " + lutName);
                        } else {
                            Log.w(TAG, "encodeAndFinishAsync: LUT bitmap not found for " + lutName);
                        }
                    }

                    if (needsUpscale) {
                        int targetW = current.getWidth() * 2;
                        int targetH = current.getHeight() * 2;
                        Bitmap scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true);
                        current.recycle();
                        current = scaled;
                        width = targetW;
                        height = targetH;
                        changed = true;
                        Log.i(TAG, "encodeAndFinishAsync: front camera upscaled to " + width + "x" + height);
                    }

                    if (changed) {
                        ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
                        current.compress(Bitmap.CompressFormat.JPEG, 95, finalOut);
                        jpegBytes = finalOut.toByteArray();
                    }
                    current.recycle();
                } else {
                    Log.w(TAG, "encodeAndFinishAsync: decode failed, keeping unfiltered/native output");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "encodeAndFinishAsync: encode failed", t);
            return;
        }
        finishAsync(context, jpegBytes, width, height);
    }

    private static String getActiveLutName() {
        try {
            Class<?> effectClass = Class.forName("com.android.camera.effect.n");
            Object instance = effectClass.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return null;
            }
            int effectId = (Integer) effectClass
                    .getMethod("getEffectForSaving", boolean.class)
                    .invoke(instance, false);
            String name = (String) effectClass
                    .getMethod("getLookupTableName", int.class)
                    .invoke(instance, effectId);
            if (name == null || name.isEmpty()) {
                return null;
            }
            Log.i(TAG, "getActiveLutName: effectId=" + effectId + " name=" + name);
            return name;
        } catch (Throwable t) {
            Log.e(TAG, "getActiveLutName failed", t);
            return null;
        }
    }

    private static Bitmap loadLutBitmap(Context context, String name) {
        try {
            int resId = context.getResources().getIdentifier(name, "raw", context.getPackageName());
            if (resId == 0) {
                Log.w(TAG, "loadLutBitmap: no raw resource named " + name);
                return null;
            }
            return BitmapFactory.decodeStream(context.getResources().openRawResource(resId));
        } catch (Throwable t) {
            Log.e(TAG, "loadLutBitmap failed for " + name, t);
            return null;
        }
    }

    private static Bitmap applyColorLookup(Bitmap source, Bitmap lut) {
        int w = source.getWidth();
        int h = source.getHeight();
        int lutSize = lut.getWidth();
        int cell = lutSize / 8;
        float maxIndex = cell - 1;

        int[] lutPixels = new int[lutSize * lutSize];
        lut.getPixels(lutPixels, 0, lutSize, 0, 0, lutSize, lutSize);

        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;

            float blueColor = b * maxIndex / 255f;
            int quad1y = (int) Math.floor(blueColor / 8f);
            int quad1x = (int) Math.floor(blueColor) - quad1y * 8;
            int quad2y = (int) Math.ceil(blueColor / 8f);
            int quad2x = (int) Math.ceil(blueColor) - quad2y * 8;

            float redRatio = r / 255f;
            float greenRatio = g / 255f;

            int px1 = clampInt(Math.round(quad1x * cell + redRatio * maxIndex), lutSize);
            int py1 = clampInt(Math.round(quad1y * cell + greenRatio * maxIndex), lutSize);
            int c1 = lutPixels[py1 * lutSize + px1];

            int px2 = clampInt(Math.round(quad2x * cell + redRatio * maxIndex), lutSize);
            int py2 = clampInt(Math.round(quad2y * cell + greenRatio * maxIndex), lutSize);
            int c2 = lutPixels[py2 * lutSize + px2];

            float frac = blueColor - (float) Math.floor(blueColor);

            int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
            int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;

            int outR = Math.round(r1 + (r2 - r1) * frac);
            int outG = Math.round(g1 + (g2 - g1) * frac);
            int outB = Math.round(b1 + (b2 - b1) * frac);

            pixels[i] = (p & 0xFF000000) | (outR << 16) | (outG << 8) | outB;
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    private static int clampInt(int value, int size) {
        if (value < 0) {
            return 0;
        }
        if (value >= size) {
            return size - 1;
        }
        return value;
    }

    private static CamInfo resolveCamInfo(Context context, int width, int height) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                return new CamInfo(90, false);
            }
            int bestOrientation = 90;
            boolean bestFacingFront = false;
            long bestScore = Long.MAX_VALUE;
            boolean found = false;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Rect activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (activeArray == null || sensorOrientation == null) {
                    continue;
                }
                long score = Math.abs((long) activeArray.width() * activeArray.height() - (long) width * height);
                if (score < bestScore) {
                    bestScore = score;
                    bestOrientation = sensorOrientation;
                    bestFacingFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                    found = true;
                }
            }
            if (!found) {
                Log.w(TAG, "resolveCamInfo: no camera characteristics matched " + width + "x" + height + ", defaulting to rear/90");
            }
            return new CamInfo(((bestOrientation % 360) + 360) % 360, bestFacingFront);
        } catch (Throwable t) {
            Log.e(TAG, "resolveCamInfo failed, defaulting to rear/90", t);
            return new CamInfo(90, false);
        }
    }

    private static byte[] rotateNv21_90CW(byte[] nv21, int width, int height) {
        int frameSize = width * height;
        byte[] out = new byte[nv21.length];
        int newWidth = height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dstX = height - 1 - y;
                int dstY = x;
                out[dstY * newWidth + dstX] = nv21[y * width + x];
            }
        }
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int newChromaWidth = chromaHeight;
        for (int cy = 0; cy < chromaHeight; cy++) {
            for (int cx = 0; cx < chromaWidth; cx++) {
                int dstCx = chromaHeight - 1 - cy;
                int dstCy = cx;
                int srcOff = frameSize + (cy * chromaWidth + cx) * 2;
                int dstOff = frameSize + (dstCy * newChromaWidth + dstCx) * 2;
                out[dstOff] = nv21[srcOff];
                out[dstOff + 1] = nv21[srcOff + 1];
            }
        }
        return out;
    }

    private static void finishAsync(Context context, byte[] jpegBytes, int width, int height) {
        try {
            Long mediaStoreId = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                mediaStoreId = findPendingMediaStoreId(context);
                if (mediaStoreId != null) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            ContentResolver resolver = context.getContentResolver();

            if (mediaStoreId == null) {
                Log.w(TAG, "no pending mediaStore entry found after retries, publishing a new entry instead");
                publishNewEntry(context, resolver, jpegBytes, width, height);
                return;
            }

            Uri itemUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId);
            Uri pendingUri = MediaStore.setIncludePending(itemUri);

            try (Cursor c = resolver.query(pendingUri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    long curSize = c.getLong(0);
                    if (curSize > MAX_PLACEHOLDER_SIZE) {
                        Log.i(TAG, "target already looks fixed (" + curSize + " bytes), skipping id=" + mediaStoreId);
                        return;
                    }
                }
            }

            try (OutputStream os = resolver.openOutputStream(pendingUri, "wt")) {
                if (os == null) {
                    Log.w(TAG, "openOutputStream returned null for id=" + mediaStoreId);
                    return;
                }
                os.write(jpegBytes);
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.SIZE, jpegBytes.length);
            values.put(MediaStore.MediaColumns.WIDTH, width);
            values.put(MediaStore.MediaColumns.HEIGHT, height);
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);
            int updated = resolver.update(pendingUri, values, null, null);

            Log.i(TAG, "finishAsync: published id=" + mediaStoreId + " rows=" + updated
                    + " " + width + "x" + height + " " + jpegBytes.length + " bytes");

            clearProcessingEntry(context, mediaStoreId);
        } catch (Throwable t) {
            Log.e(TAG, "finishAsync failed", t);
        }
    }

    private static void publishNewEntry(Context context, ContentResolver resolver, byte[] jpegBytes, int width, int height) {
        try {
            String name = "IMG_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date()) + ".jpg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.WIDTH, width);
            values.put(MediaStore.MediaColumns.HEIGHT, height);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000);

            Uri itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) {
                Log.w(TAG, "publishNewEntry: insert returned null");
                return;
            }

            try (OutputStream os = resolver.openOutputStream(itemUri)) {
                if (os == null) {
                    Log.w(TAG, "publishNewEntry: openOutputStream returned null for " + itemUri);
                    return;
                }
                os.write(jpegBytes);
            }

            ContentValues doneValues = new ContentValues();
            doneValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(itemUri, doneValues, null, null);

            Log.i(TAG, "publishNewEntry: created " + itemUri + " " + width + "x" + height + " " + jpegBytes.length + " bytes");
        } catch (Throwable t) {
            Log.e(TAG, "publishNewEntry failed", t);
        }
    }

    private static Long findPendingMediaStoreId(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri listUri = Uri.parse("content://" + PROCESSING_AUTHORITY + "/processing");
            Cursor c = resolver.query(listUri, null, null, null, null);
            if (c == null) {
                return null;
            }
            try {
                int idIdx = c.getColumnIndex("media_store_id");
                if (idIdx < 0) {
                    return null;
                }
                if (c.moveToFirst()) {
                    return c.getLong(idIdx);
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "findPendingMediaStoreId failed", t);
        }
        return null;
    }

    private static void clearProcessingEntry(Context context, long mediaStoreId) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri deleteUri = Uri.parse("content://" + PROCESSING_AUTHORITY + "/processing/" + mediaStoreId);
            int rows = resolver.delete(deleteUri, null, null);
            Log.i(TAG, "clearProcessingEntry: id=" + mediaStoreId + " rows=" + rows);
        } catch (Throwable t) {
            Log.e(TAG, "clearProcessingEntry failed", t);
        }
    }

    private static byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        byte[] nv21 = new byte[width * height * 3 / 2];

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            int rowStart = row * yRowStride;
            if (yPixelStride == 1) {
                yBuffer.position(rowStart);
                yBuffer.get(nv21, pos, width);
                pos += width;
            } else {
                for (int col = 0; col < width; col++) {
                    nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride);
                }
            }
        }

        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        for (int row = 0; row < chromaHeight; row++) {
            int uvRowStart = row * uvRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int uvIndex = uvRowStart + col * uvPixelStride;
                nv21[pos++] = uBuffer.get(uvIndex);
                nv21[pos++] = vBuffer.get(uvIndex);
            }
        }

        return nv21;
    }
}
