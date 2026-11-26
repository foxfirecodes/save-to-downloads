package com.foxfire.savetodownloads;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class SaveActivity extends Activity {
    private static final String TAG = "SaveToDownloads";
    private static final Uri DOWNLOADS =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    private CompletableFuture<Integer> save;
    private CompletableFuture<Void> completion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Set<Uri> files = sharedFiles(getIntent());
        if (files.isEmpty()) {
            showResult(getString(R.string.no_files));
            return;
        }

        Object retainedSave = getLastNonConfigurationInstance();
        if (retainedSave != null) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Integer> runningSave = (CompletableFuture<Integer>) retainedSave;
            save = runningSave;
        } else if (savedInstanceState != null) {
            // Restarting after process death could copy files that were already saved.
            showResult(getString(R.string.interrupted));
            return;
        } else {
            ContentResolver resolver = getApplicationContext().getContentResolver();
            save = CompletableFuture.supplyAsync(() -> saveFiles(resolver, files));
        }

        setContentView(R.layout.saving);
        completion = save.thenAcceptAsync(count -> {
            String message;
            if (count == 0) {
                message = getString(R.string.save_failed);
            } else if (count < files.size()) {
                message = getString(R.string.saved_some, count, files.size());
            } else {
                message = getResources().getQuantityString(R.plurals.saved_files, count, count);
            }
            showResult(message);
        }, getMainExecutor());
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return save;
    }

    @Override
    protected void onDestroy() {
        if (completion != null) {
            completion.cancel(false);
        }
        super.onDestroy();
    }

    private void showResult(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        finish();
    }

    @SuppressWarnings("deprecation") // The legacy parcelable getters support Android 10–12.
    private static Set<Uri> sharedFiles(Intent intent) {
        Set<Uri> files = new LinkedHashSet<>();
        boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction());
        if (!multiple && !Intent.ACTION_SEND.equals(intent.getAction())) {
            return files;
        }

        try {
            if (multiple) {
                var streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) {
                    for (Object stream : streams) {
                        addFile(files, stream);
                    }
                }
            } else {
                addFile(files, intent.getParcelableExtra(Intent.EXTRA_STREAM));
            }
        } catch (BadParcelableException e) {
            Log.w(TAG, "Could not read shared file extras", e);
        }

        // Senders often put the same URIs in both the extras and ClipData.
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                addFile(files, clip.getItemAt(index).getUri());
            }
        }
        return files;
    }

    private static void addFile(Set<Uri> files, Object item) {
        if (item instanceof Uri) {
            Uri uri = (Uri) item;
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                files.add(uri);
            }
        }
    }

    private static int saveFiles(ContentResolver resolver, Set<Uri> files) {
        int saved = 0;
        for (Uri file : files) {
            try {
                saveFile(resolver, file);
                saved++;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not save shared file", e);
            }
        }
        return saved;
    }

    private static void saveFile(ContentResolver resolver, Uri source) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaColumns.DISPLAY_NAME, displayName(resolver, source));
        values.put(MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaColumns.IS_PENDING, 1);

        Uri destination = resolver.insert(DOWNLOADS, values);
        if (destination == null) {
            throw new IOException("Could not create a Downloads entry");
        }

        boolean published = false;
        try {
            try (InputStream input = resolver.openInputStream(source);
                    OutputStream output = resolver.openOutputStream(destination)) {
                if (input == null || output == null) {
                    throw new IOException("Could not open a file stream");
                }
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaColumns.IS_PENDING, 0);
            published = resolver.update(destination, ready, null, null) > 0;
            if (!published) {
                throw new IOException("Could not publish the downloaded file");
            }
        } finally {
            if (!published) {
                try {
                    resolver.delete(destination, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Could not remove incomplete download", e);
                }
            }
        }
    }

    private static String displayName(ContentResolver resolver, Uri source) {
        try (Cursor cursor = resolver.query(source, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String name = index == -1 ? null : cursor.getString(index);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read the original filename", e);
        }
        return "shared-file";
    }
}
