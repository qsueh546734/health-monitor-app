package android.content;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public interface DialogInterface {
    interface OnClickListener {
        void onClick(DialogInterface dialog, int which);
    }
    void dismiss();
}
