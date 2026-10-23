package org.runnerup.view;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.runnerup.R;
import org.runnerup.db.Backup;
import org.runnerup.db.DBHelper;

public class SettingsMaintenanceFragment extends PreferenceFragmentCompat {

  private static final String TAG = "SettingsMaintenance";

  /**
   * ActivityResultLauncher for handling the result of the {@link Intent#ACTION_CREATE_DOCUMENT}
   * intent used for exporting the database.
   *
   * <p>When the user selects a destination file, this launcher receives the {@link Uri} and
   * initiates the database export process via {@link DBHelper#exportDatabase(Context, Uri)}. If the
   * export is cancelled or no Uri is returned, a toast message is shown and a warning is logged.
   */
  private final ActivityResultLauncher<Intent> exportDbLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            Uri toUri = getUriFromResult(result);
            if (toUri != null) {
              DBHelper.exportDatabase(requireContext(), toUri);
            } else {
              Toast.makeText(
                      requireContext(),
                      org.runnerup.common.R.string.export_cancelled,
                      Toast.LENGTH_SHORT)
                  .show();
              Log.w(TAG, "Export cancelled or URI not found.");
            }
          });

  /**
   * ActivityResultLauncher for handling the result of the {@link Intent#ACTION_OPEN_DOCUMENT}
   * intent used for importing the database.
   *
   * <p>When the user selects a database file, this launcher receives its {@link Uri} and initiates
   * the database import process via {@link DBHelper#importDatabase(Context, Uri)}. If the import is
   * cancelled or no Uri is returned, a toast message is shown and a warning is logged.
   */
  private final ActivityResultLauncher<Intent> importDbLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            Uri fromUri = getUriFromResult(result);
            if (fromUri != null) {
              DBHelper.importDatabase(requireContext(), fromUri);
            } else {
              Toast.makeText(
                      requireContext(),
                      org.runnerup.common.R.string.import_cancelled,
                      Toast.LENGTH_SHORT)
                  .show();
              Log.w(TAG, "Import cancelled or URI not found.");
            }
          });

  /** Picks (creates) the backup file, then writes the first backup to it */
  private final ActivityResultLauncher<Intent> chooseBackupLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            Uri uri = getUriFromResult(result);
            if (uri == null) {
              return;
            }
            try {
              Backup.setUri(requireContext(), uri);
            } catch (SecurityException e) {
              Log.w(TAG, "No lasting permission for " + uri, e);
              Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
              return;
            }
            Backup.schedule(requireContext());
            backupNow();
          });

  private final ActivityResultLauncher<Intent> restoreLauncher =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            Uri uri = getUriFromResult(result);
            if (uri != null) {
              confirmRestore(uri);
            }
          });

  private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** Updates the backup summaries and schedule when the backup settings change */
  private final SharedPreferences.OnSharedPreferenceChangeListener backupPrefsListener =
      (prefs, key) -> {
        if (key == null || !key.startsWith("pref_backup_") || !isAdded()) {
          return;
        }
        if (key.equals(getString(R.string.pref_backup_auto))
            || key.equals(getString(R.string.pref_backup_wifi))) {
          Backup.schedule(requireContext());
        }
        updateBackupSummaries();
      };

  private final Preference.OnPreferenceClickListener onExportClick =
      preference -> {
        Intent intent =
            new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // Use "application/octet-stream" to be consistent with the mime type used in
                // the http intent-filter for MainLayout (in AndroidManifest).
                .setType("application/octet-stream")
                .putExtra(
                    Intent.EXTRA_TITLE,
                    "runnerup.db.export"); // Suggest a name (note: user may change it)
        exportDbLauncher.launch(intent);
        return true;
      };
  private final Preference.OnPreferenceClickListener onImportClick =
      preference -> {
        Intent intent =
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        importDbLauncher.launch(intent);
        return true;
      };
  private final Preference.OnPreferenceClickListener onPruneClick =
      preference -> {
        final ProgressDialog dialog = new ProgressDialog(requireContext());
        dialog.setTitle(org.runnerup.common.R.string.Pruning_deleted_activities_from_database);
        dialog.show();
        DBHelper.purgeDeletedActivities(requireContext(), dialog, dialog::dismiss);
        return false;
      };

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    setPreferencesFromResource(R.xml.settings_maintenance, rootKey);
    Resources res = getResources();
    setClick(
        R.string.pref_backup_location,
        () ->
            chooseBackupLauncher.launch(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "runnerup-backup.zip")));
    setClick(R.string.pref_backup_now, this::backupNow);
    setClick(
        R.string.pref_backup_restore,
        () ->
            restoreLauncher.launch(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    // Any file: Drive does not always know the type of a backup
                    .setType("*/*")));
    updateBackupSummaries();
    {
      Preference btn = findPreference(res.getString(R.string.pref_exportdb));
      if (btn != null) {
        btn.setOnPreferenceClickListener(onExportClick);
      }
    }
    {
      Preference btn = findPreference(res.getString(R.string.pref_importdb));
      if (btn != null) {
        btn.setOnPreferenceClickListener(onImportClick);
      }
    }
    {
      Preference btn = findPreference(res.getString(R.string.pref_prunedb));
      if (btn != null) {
        btn.setOnPreferenceClickListener(onPruneClick);
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    PreferenceManager.getDefaultSharedPreferences(requireContext())
        .registerOnSharedPreferenceChangeListener(backupPrefsListener);
    updateBackupSummaries();
  }

  @Override
  public void onPause() {
    super.onPause();
    PreferenceManager.getDefaultSharedPreferences(requireContext())
        .unregisterOnSharedPreferenceChangeListener(backupPrefsListener);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    backupExecutor.shutdown();
  }

  private void setClick(int key, Runnable action) {
    Preference pref = findPreference(getString(key));
    if (pref != null) {
      pref.setOnPreferenceClickListener(
          p -> {
            action.run();
            return true;
          });
    }
  }

  /** Backup file name, last backup and which backup settings can be used */
  private void updateBackupSummaries() {
    Context ctx = requireContext();
    Uri uri = Backup.getUri(ctx);
    Preference location = findPreference(getString(R.string.pref_backup_location));
    Preference now = findPreference(getString(R.string.pref_backup_now));
    for (int key : new int[] {R.string.pref_backup_auto, R.string.pref_backup_wifi}) {
      Preference pref = findPreference(getString(key));
      if (pref != null) {
        pref.setEnabled(uri != null);
      }
    }
    if (now != null) {
      now.setEnabled(uri != null);
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
      String error = prefs.getString(getString(R.string.pref_backup_last_error), null);
      long time = prefs.getLong(getString(R.string.pref_backup_last_time), 0);
      if (error != null) {
        now.setSummary(getString(org.runnerup.common.R.string.Backup_failed, error));
      } else if (time > 0) {
        Date date = new Date(time);
        String text =
            DateFormat.getDateFormat(ctx).format(date)
                + " "
                + DateFormat.getTimeFormat(ctx).format(date);
        now.setSummary(getString(org.runnerup.common.R.string.Backup_last, text));
      } else {
        now.setSummary(org.runnerup.common.R.string.Backup_never);
      }
    }
    if (location == null) {
      return;
    }
    if (uri == null) {
      location.setSummary(org.runnerup.common.R.string.Backup_location_none);
      return;
    }
    // The file name, asked to the provider (e.g. Drive) in the background
    backupExecutor.execute(
        () -> {
          String name = displayName(ctx, uri);
          mainHandler.post(
              () -> {
                if (isAdded()) {
                  if (name != null) {
                    location.setSummary(name);
                  } else {
                    location.setSummary(org.runnerup.common.R.string.Backup_location_unavailable);
                  }
                }
              });
        });
  }

  @Nullable
  private static String displayName(Context ctx, Uri uri) {
    try (Cursor c =
        ctx.getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        return c.getString(0);
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Backup file not available: " + uri, e);
    }
    return null;
  }

  private void backupNow() {
    Context ctx = requireContext().getApplicationContext();
    Preference now = findPreference(getString(R.string.pref_backup_now));
    if (now != null) {
      now.setEnabled(false);
      now.setSummary(org.runnerup.common.R.string.Backup_running);
    }
    backupExecutor.execute(
        () -> {
          String error = null;
          try {
            Backup.backupNow(ctx);
          } catch (IOException e) {
            error = e.getMessage();
          }
          String message = error;
          mainHandler.post(
              () -> {
                if (!isAdded()) {
                  return;
                }
                if (message == null) {
                  Toast.makeText(ctx, org.runnerup.common.R.string.Backup_done, Toast.LENGTH_SHORT)
                      .show();
                }
                updateBackupSummaries();
              });
        });
  }

  private void confirmRestore(Uri uri) {
    new AlertDialog.Builder(requireContext())
        .setTitle(org.runnerup.common.R.string.Backup_restore)
        .setMessage(org.runnerup.common.R.string.Backup_restore_confirm)
        .setPositiveButton(
            org.runnerup.common.R.string.Backup_restore_action, (d, w) -> restore(uri))
        .setNegativeButton(org.runnerup.common.R.string.Cancel, null)
        .show();
  }

  private void restore(Uri uri) {
    Context ctx = requireContext();
    final ProgressDialog progress = new ProgressDialog(ctx);
    progress.setMessage(getString(org.runnerup.common.R.string.Backup_restoring));
    progress.setCancelable(false);
    progress.show();
    backupExecutor.execute(
        () -> {
          String error = null;
          try {
            Backup.restore(ctx.getApplicationContext(), uri);
          } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Restore failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
          }
          String message = error;
          mainHandler.post(
              () -> {
                progress.dismiss();
                if (message == null) {
                  new AlertDialog.Builder(ctx)
                      .setMessage(org.runnerup.common.R.string.Backup_restore_done)
                      .setCancelable(false)
                      .setPositiveButton(
                          org.runnerup.common.R.string.OK,
                          (d, w) -> Backup.restartApp(ctx.getApplicationContext()))
                      .show();
                } else {
                  new AlertDialog.Builder(ctx)
                      .setMessage(
                          getString(org.runnerup.common.R.string.Backup_restore_failed, message))
                      .setPositiveButton(org.runnerup.common.R.string.OK, null)
                      .show();
                }
              });
        });
  }

  /**
   * Helper method to check the result of an ActivityResult and extract the Uri.
   *
   * @param result The ActivityResult from the launcher.
   * @return The Uri if the result was OK and data is present, otherwise null.
   */
  @Nullable
  private Uri getUriFromResult(ActivityResult result) {
    if (result.getResultCode() == Activity.RESULT_OK) {
      Intent data = result.getData();
      if (data != null && data.getData() != null) {
        return data.getData();
      }
    }
    return null;
  }
}
