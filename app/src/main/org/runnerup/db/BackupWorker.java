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
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;

/** Automatic backup in the background, scheduled by {@link Backup} */
public class BackupWorker extends Worker {

  private static final int MAX_ATTEMPTS = 3;

  public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public Result doWork() {
    if (Backup.getUri(getApplicationContext()) == null) {
      return Result.success();
    }
    try {
      Backup.backupNow(getApplicationContext());
      return Result.success();
    } catch (IOException | SecurityException e) {
      // The result is shown in Settings > Maintenance
      Log.w("BackupWorker", "Backup failed", e);
      return getRunAttemptCount() < MAX_ATTEMPTS ? Result.retry() : Result.failure();
    }
  }
}
