// SPDX-FileCopyrightText: 2026 Andrew Yong
// SPDX-License-Identifier: MIT

package android.os;

/** Compile-time stub of the @SystemApi class; the framework provides it at run time. */
public class UpdateEngine {
    public static final class UpdateStatusConstants {
        public static final int IDLE = 0;
        public static final int CHECKING_FOR_UPDATE = 1;
        public static final int UPDATE_AVAILABLE = 2;
        public static final int DOWNLOADING = 3;
        public static final int VERIFYING = 4;
        public static final int FINALIZING = 5;
        public static final int UPDATED_NEED_REBOOT = 6;
        public static final int REPORTING_ERROR_EVENT = 7;
        public static final int ATTEMPTING_ROLLBACK = 8;
        public static final int DISABLED = 9;
    }

    public UpdateEngine() { throw new RuntimeException("stub"); }
    public boolean bind(UpdateEngineCallback callback, Handler handler) { throw new RuntimeException("stub"); }
    public boolean unbind() { throw new RuntimeException("stub"); }
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) { throw new RuntimeException("stub"); }
    public void suspend() { throw new RuntimeException("stub"); }
    public void resume() { throw new RuntimeException("stub"); }
    public void cancel() { throw new RuntimeException("stub"); }
    public void resetStatus() { throw new RuntimeException("stub"); }
}
