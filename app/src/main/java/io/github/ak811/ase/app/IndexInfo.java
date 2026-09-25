package io.github.ak811.ase.app;

/** What is currently loaded. */
public final class IndexInfo {
    public enum Source { BUNDLED, IMPORTED }

    public final int documentCount;
    public final Source source;
    public final boolean bundledAvailable;

    IndexInfo(int documentCount, Source source, boolean bundledAvailable) {
        this.documentCount = documentCount;
        this.source = source;
        this.bundledAvailable = bundledAvailable;
    }

    /** True when an imported index is active and the APK also ships one to go back to. */
    public boolean canRestoreBundled() {
        return source == Source.IMPORTED && bundledAvailable;
    }
}
