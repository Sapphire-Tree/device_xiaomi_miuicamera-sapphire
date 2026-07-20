package lunaris.camerafix;

import android.util.Log;

public class FilterState {
    private static final String TAG = "RawJpegFix";
    public static volatile String currentLutName = null;

    public static void setCurrentLut(String name) {
        try {
            currentLutName = name;
            Log.i(TAG, "FilterState: active color-lookup filter = " + name);
        } catch (Throwable ignored) {
        }
    }
}
