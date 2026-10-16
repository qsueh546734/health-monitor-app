package android.app;

import android.content.Context;
import android.content.DialogInterface;

/** 【桩】见 android/content/ContentValues.java 的说明。 */
public class AlertDialog {

    public static class Builder {
        public Builder(Context context) {}
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setTitle(int resId) { return this; }
        public Builder setMessage(CharSequence msg) { return this; }
        public Builder setMessage(int resId) { return this; }
        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) { return this; }
        public Builder setPositiveButton(int resId, DialogInterface.OnClickListener l) { return this; }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) { return this; }
        public Builder setCancelable(boolean cancelable) { return this; }
        public AlertDialog create() { return new AlertDialog(); }
        public AlertDialog show() { return new AlertDialog(); }
    }

    public void show() {}

    public void dismiss() {}

    public void setMessage(CharSequence msg) {}

    public void setTitle(CharSequence title) {}
}
