package android.media;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class ToneGenerator {

    public static final int TONE_PROP_BEEP = 24;
    public static final int TONE_PROP_BEEP2 = 25;
    public static final int TONE_CDMA_ALERT_CALL_GUARD = 0;
    public static final int MAX_VOLUME = 100;

    public ToneGenerator(int streamType, int volume) {}

    public boolean startTone(int toneType) { return true; }

    public boolean startTone(int toneType, int durationMs) { return true; }

    public void stopTone() {}

    public void release() {}
}
