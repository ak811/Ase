package io.github.ak811.ase.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.ak811.ase.core.index.IndexCodec;
import io.github.ak811.ase.core.index.SearchIndex;

/**
 * Finds and loads the index. An index the user imported takes precedence over
 * the one bundled in the APK at {@code assets/index.bin}. All methods block;
 * call them off the main thread.
 */
final class IndexRepository {
    static final String BUNDLED_ASSET = "index.bin";
    private static final String IMPORTED_FILE = "imported-index.bin";

    static final class Loaded {
        final SearchIndex index;
        final IndexInfo.Source source;

        Loaded(SearchIndex index, IndexInfo.Source source) {
            this.index = index;
            this.source = source;
        }
    }

    private final Context context;

    IndexRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Loads the active index, or returns {@code null} if there is none. */
    @Nullable
    Loaded load() throws IOException {
        File imported = importedFile();
        if (imported.isFile()) {
            try (InputStream in = new FileInputStream(imported)) {
                return new Loaded(IndexCodec.read(in), IndexInfo.Source.IMPORTED);
            }
        }
        if (hasBundledIndex()) {
            try (InputStream in = context.getAssets().open(BUNDLED_ASSET, AssetManager.ACCESS_STREAMING)) {
                return new Loaded(IndexCodec.read(in), IndexInfo.Source.BUNDLED);
            }
        }
        return null;
    }

    boolean hasBundledIndex() {
        try (InputStream ignored = context.getAssets().open(BUNDLED_ASSET)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean hasImportedIndex() {
        return importedFile().isFile();
    }

    /**
     * Copies the document at {@code uri} into app storage, validates it, and makes it
     * the active index. The previous index is kept if the new one is invalid.
     */
    SearchIndex importFrom(Uri uri) throws IOException {
        File target = importedFile();
        File temp = new File(target.getPath() + ".tmp");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temp)) {
                if (in == null) {
                    throw new FileNotFoundException(uri.toString());
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            SearchIndex index;
            try (InputStream in = new FileInputStream(temp)) {
                index = IndexCodec.read(in);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("cannot replace the previous index");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("cannot save the imported index");
            }
            return index;
        } finally {
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    boolean deleteImported() {
        File file = importedFile();
        return !file.exists() || file.delete();
    }

    private File importedFile() {
        return new File(context.getFilesDir(), IMPORTED_FILE);
    }
}
