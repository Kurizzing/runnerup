/*
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.db;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.runnerup.R;
import org.runnerup.view.MainLayout;
import org.runnerup.workout.WorkoutSerializer;

/**
 * Backup of all RunnerUp data (activities database, settings and advanced workouts) to one zip file
 * chosen by the user through the system file picker, e.g. on Google Drive. The file can be written
 * again automatically (after each activity, daily or weekly) by {@link BackupWorker}, and restored
 * from a backup file or from a database exported by the older "Export" (plain database file).
 */
public final class Backup {

  private static final String DB_ENTRY = "runnerup.db";
  private static final String PREFS_ENTRY = "preferences.json";
  private static final String WORKOUTS_ENTRY = "workouts/";

  private static final String WORK_PERIODIC = "backup_periodic";
  private static final String WORK_AFTER_ACTIVITY = "backup_after_activity";

  /** Settings files that belong to libraries, not to the user */
  private static final String SKIPPED_PREFS_PREFIX = "androidx.";

  private Backup() {}

  // Backup settings are kept as they are on this phone when restoring
  private static boolean isBackupKey(String key) {
    return key.startsWith("pref_backup_");
  }

  private static SharedPreferences prefs(Context ctx) {
    return PreferenceManager.getDefaultSharedPreferences(ctx);
  }

  /**
   * @return the backup file chosen by the user, null if none
   */
  @Nullable
  public static Uri getUri(Context ctx) {
    String uri = prefs(ctx).getString(ctx.getString(R.string.pref_backup_uri), null);
    return uri == null ? null : Uri.parse(uri);
  }

  /** Remembers the backup file, keeping the permission to write it across restarts */
  public static void setUri(Context ctx, Uri uri) {
    Uri old = getUri(ctx);
    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    if (old != null && !old.equals(uri)) {
      try {
        ctx.getContentResolver().releasePersistableUriPermission(old, flags);
      } catch (SecurityException e) {
        // Already gone
      }
    }
    ctx.getContentResolver().takePersistableUriPermission(uri, flags);
    prefs(ctx).edit().putString(ctx.getString(R.string.pref_backup_uri), uri.toString()).apply();
  }

  /** Writes the backup to the chosen file and records the result shown in the settings */
  public static void backupNow(Context ctx) throws IOException {
    Uri uri = getUri(ctx);
    if (uri == null) {
      throw new IOException("No backup file chosen");
    }
    try {
      write(ctx, uri);
      prefs(ctx)
          .edit()
          .putLong(ctx.getString(R.string.pref_backup_last_time), System.currentTimeMillis())
          .remove(ctx.getString(R.string.pref_backup_last_error))
          .apply();
    } catch (IOException | RuntimeException e) {
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      prefs(ctx).edit().putString(ctx.getString(R.string.pref_backup_last_error), message).apply();
      throw e instanceof IOException io ? io : new IOException(message, e);
    }
  }

  private static void write(Context ctx, Uri to) throws IOException {
    OutputStream out = ctx.getContentResolver().openOutputStream(to, "wt");
    if (out == null) {
      throw new IOException("Cannot open " + to);
    }
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      // Database, copied while no one else writes to it
      SQLiteDatabase db = DBHelper.getWritableDatabase(ctx);
      db.beginTransaction();
      try {
        zip.putNextEntry(new ZipEntry(DB_ENTRY));
        readFile(new File(DBHelper.getDbPath(ctx)), zip);
        zip.closeEntry();
      } finally {
        db.endTransaction();
        DBHelper.closeDB(db);
      }

      zip.putNextEntry(new ZipEntry(PREFS_ENTRY));
      zip.write(preferencesToJson(ctx).toString().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();

      File[] workouts = workoutsDir(ctx).listFiles();
      if (workouts != null) {
        for (File f : workouts) {
          if (f.isFile()) {
            zip.putNextEntry(new ZipEntry(WORKOUTS_ENTRY + f.getName()));
            readFile(f, zip);
            zip.closeEntry();
          }
        }
      }
    }
  }

  /**
   * Replaces the data on this phone with the backup (or an exported database). The app must be
   * restarted afterwards, see {@link #restartApp}.
   */
  public static void restore(Context ctx, Uri from) throws IOException {
    File dir = new File(ctx.getCacheDir(), "restore");
    deleteRecursive(dir);
    if (!dir.mkdirs()) {
      throw new IOException("Cannot create " + dir);
    }
    try {
      File db = new File(dir, DB_ENTRY);
      File workouts = new File(dir, "workouts");
      String prefsJson = null;

      InputStream raw = ctx.getContentResolver().openInputStream(from);
      if (raw == null) {
        throw new IOException("Cannot open " + from);
      }
      try (BufferedInputStream in = new BufferedInputStream(raw)) {
        in.mark(4);
        boolean isZip = in.read() == 'P' && in.read() == 'K';
        in.reset();
        if (isZip) {
          try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
              String name = entry.getName();
              if (name.equals(DB_ENTRY)) {
                writeFile(zip, db);
              } else if (name.equals(PREFS_ENTRY)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                copyStream(zip, bytes);
                prefsJson = bytes.toString("UTF-8");
              } else if (name.startsWith(WORKOUTS_ENTRY)) {
                String file = new File(name.substring(WORKOUTS_ENTRY.length())).getName();
                if (!file.isEmpty() && (workouts.isDirectory() || workouts.mkdirs())) {
                  writeFile(zip, new File(workouts, file));
                }
              }
            }
          }
        } else {
          // Database exported by Maintenance > Export
          writeFile(in, db);
        }
      }

      if (!db.exists() || !DBHelper.isValidRunnerUpDatabase(ctx, Uri.fromFile(db))) {
        throw new IOException(
            ctx.getString(org.runnerup.common.R.string.import_error_invalid_database));
      }

      // Database
      DBHelper.getHelper(ctx).close();
      File target = new File(DBHelper.getDbPath(ctx));
      try (InputStream in = new FileInputStream(db)) {
        writeFile(in, target);
      }
      //noinspection ResultOfMethodCallIgnored
      new File(target.getPath() + "-journal").delete();

      // Settings
      if (prefsJson != null) {
        try {
          preferencesFromJson(ctx, new JSONObject(prefsJson));
        } catch (JSONException e) {
          throw new IOException("Invalid settings in the backup", e);
        }
      }

      // Workouts
      if (workouts.isDirectory()) {
        File current = workoutsDir(ctx);
        File[] old = current.listFiles();
        if (old != null) {
          for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
          }
        }
        File[] restored = workouts.listFiles();
        if (restored != null) {
          for (File f : restored) {
            try (InputStream in = new FileInputStream(f)) {
              writeFile(in, new File(current, f.getName()));
            }
          }
        }
      }
    } finally {
      deleteRecursive(dir);
    }
  }

  /** Restarts the app, so the restored database and settings are used */
  public static void restartApp(Context ctx) {
    Intent intent = new Intent(ctx, MainLayout.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    ctx.startActivity(intent);
    Runtime.getRuntime().exit(0);
  }

  // Automatic backups

  /** Schedules (or cancels) the daily or weekly backup, after the backup settings changed */
  public static void schedule(Context ctx) {
    WorkManager work = WorkManager.getInstance(ctx);
    String mode = autoMode(ctx);
    if (getUri(ctx) == null || !("daily".equals(mode) || "weekly".equals(mode))) {
      work.cancelUniqueWork(WORK_PERIODIC);
    } else {
      PeriodicWorkRequest request =
          new PeriodicWorkRequest.Builder(
                  BackupWorker.class, "daily".equals(mode) ? 1 : 7, TimeUnit.DAYS)
              .setConstraints(constraints(ctx))
              .build();
      work.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
    if (!"activity".equals(mode)) {
      work.cancelUniqueWork(WORK_AFTER_ACTIVITY);
    }
  }

  /** Backs up soon after an activity was saved, if chosen in the settings */
  public static void onActivitySaved(Context ctx) {
    if (getUri(ctx) == null || !"activity".equals(autoMode(ctx))) {
      return;
    }
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(BackupWorker.class)
            .setConstraints(constraints(ctx))
            // Several activities saved in a row give one backup
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build();
    WorkManager.getInstance(ctx)
        .enqueueUniqueWork(WORK_AFTER_ACTIVITY, ExistingWorkPolicy.REPLACE, request);
  }

  private static String autoMode(Context ctx) {
    return prefs(ctx).getString(ctx.getString(R.string.pref_backup_auto), "off");
  }

  private static Constraints constraints(Context ctx) {
    boolean wifi = prefs(ctx).getBoolean(ctx.getString(R.string.pref_backup_wifi), false);
    return new Constraints.Builder()
        .setRequiredNetworkType(wifi ? NetworkType.UNMETERED : NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build();
  }

  // Settings as JSON: {file: {key: {"t": type, "v": value}}}

  private static JSONObject preferencesToJson(Context ctx) throws IOException {
    JSONObject files = new JSONObject();
    try {
      for (String name : prefsFiles(ctx)) {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> e : ctx.getSharedPreferences(name, 0).getAll().entrySet()) {
          if (isBackupKey(e.getKey()) || e.getValue() == null) {
            continue;
          }
          Object v = e.getValue();
          JSONObject value = new JSONObject();
          if (v instanceof Boolean) {
            value.put("t", "b");
          } else if (v instanceof Integer) {
            value.put("t", "i");
          } else if (v instanceof Long) {
            value.put("t", "l");
          } else if (v instanceof Float) {
            value.put("t", "f");
          } else if (v instanceof Set) {
            value.put("t", "set");
            v = new JSONArray((Set<?>) v);
          } else {
            value.put("t", "s");
            v = v.toString();
          }
          value.put("v", v);
          values.put(e.getKey(), value);
        }
        files.put(name, values);
      }
    } catch (JSONException e) {
      throw new IOException(e);
    }
    return files;
  }

  private static void preferencesFromJson(Context ctx, JSONObject files) throws JSONException {
    String defaultName = ctx.getPackageName() + "_preferences";
    Iterator<String> names = files.keys();
    while (names.hasNext()) {
      String name = names.next();
      if (name.startsWith(SKIPPED_PREFS_PREFIX)) {
        continue;
      }
      SharedPreferences sp = ctx.getSharedPreferences(name, 0);
      SharedPreferences.Editor editor = sp.edit();
      // Replace all values, except the backup settings of this phone
      for (String key : sp.getAll().keySet()) {
        if (!(name.equals(defaultName) && isBackupKey(key))) {
          editor.remove(key);
        }
      }
      JSONObject values = files.getJSONObject(name);
      Iterator<String> keys = values.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (isBackupKey(key)) {
          continue;
        }
        JSONObject value = values.getJSONObject(key);
        switch (value.getString("t")) {
          case "b" -> editor.putBoolean(key, value.getBoolean("v"));
          case "i" -> editor.putInt(key, value.getInt("v"));
          case "l" -> editor.putLong(key, value.getLong("v"));
          case "f" -> editor.putFloat(key, (float) value.getDouble("v"));
          case "set" -> {
            JSONArray array = value.getJSONArray("v");
            Set<String> set = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
              set.add(array.getString(i));
            }
            editor.putStringSet(key, set);
          }
          default -> editor.putString(key, value.getString("v"));
        }
      }
      // Written now: the app restarts right after the restore
      //noinspection ApplySharedPref
      editor.commit();
    }
  }

  /** Names of the settings files of the app (the default settings and the audio cue schemes) */
  private static Set<String> prefsFiles(Context ctx) {
    Set<String> names = new HashSet<>();
    names.add(ctx.getPackageName() + "_preferences");
    File[] files = new File(ctx.getFilesDir().getParentFile(), "shared_prefs").listFiles();
    if (files != null) {
      for (File f : files) {
        String name = f.getName();
        if (name.endsWith(".xml") && !name.startsWith(SKIPPED_PREFS_PREFIX)) {
          names.add(name.substring(0, name.length() - ".xml".length()));
        }
      }
    }
    return names;
  }

  private static File workoutsDir(Context ctx) {
    return ctx.getDir(WorkoutSerializer.WORKOUTS_DIR, 0);
  }

  /** Copies the file to out, leaving out open (e.g. a zip entry) */
  private static void readFile(File file, OutputStream out) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      copyStream(in, out);
    }
  }

  /** Copies in to the file, leaving in open (e.g. a zip entry) */
  private static void writeFile(InputStream in, File file) throws IOException {
    try (OutputStream out = new FileOutputStream(file)) {
      copyStream(in, out);
    }
  }

  private static void copyStream(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    int n;
    while ((n = in.read(buffer)) > 0) {
      out.write(buffer, 0, n);
    }
  }

  private static void deleteRecursive(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursive(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }
}
