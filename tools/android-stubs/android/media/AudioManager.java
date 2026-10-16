package android.media;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class AudioManager {

    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_NOTIFICATION = 5;
    public static final int STREAM_ALARM = 4;

    public void setStreamVolume(int streamType, int index, int flags) {}

    public int getStreamVolume(int streamType) { return 0; }
}
