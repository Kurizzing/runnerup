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

package org.runnerup.view;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.LongSparseArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.runnerup.common.util.Constants.DB;
import org.runnerup.workout.Sport;

/**
 * All-time running statistics and records (top 3): best efforts over standard distances anywhere
 * within a run (like Strava), fastest 1 km splits and longest runs. Each run contributes at most
 * one entry per list.
 */
final class RunningRecords {

  /** Distances (m) of the best efforts */
  static final double[] EFFORT_DISTANCES = {1000, 5000, 10000, 21097.5, 42195};

  static final int TOP = 3;
  private static final double SPLIT_DISTANCE = 1000;
  // Faster than this between two GPS points is a position jump, not running
  private static final double MAX_SPEED = 12;

  /** A record: an activity and its result (seconds for times, meters for distances) */
  static final class Entry {
    final long activityId;
    final long startTime;
    final String title;
    final double value;

    Entry(long activityId, long startTime, String title, double value) {
      this.activityId = activityId;
      this.startTime = startTime;
      this.title = title;
      this.value = value;
    }
  }

  /** A medal held by an activity: rank (0 = first) and record category */
  static final class Medal {
    final int rank;
    final String label;

    Medal(int rank, String label) {
      this.rank = rank;
      this.label = label;
    }
  }

  int runCount = 0;
  double totalDistance = 0;
  long totalTime = 0;

  /** Per distance in EFFORT_DISTANCES, the fastest times */
  final List<List<Entry>> bestEfforts = new ArrayList<>();

  final List<Entry> fastestSplits = new ArrayList<>();
  final List<Entry> longestRuns = new ArrayList<>();

  private RunningRecords() {
    for (int i = 0; i < EFFORT_DISTANCES.length; i++) {
      bestEfforts.add(new ArrayList<>());
    }
  }

  static boolean isRunning(int sport) {
    return sport == DB.ACTIVITY.SPORT_RUNNING
        || sport == DB.ACTIVITY.SPORT_TREADMILL
        || sport == DB.ACTIVITY.SPORT_ORIENTEERING;
  }

  /** Reads all running activities; call on a background thread. */
  static RunningRecords compute(Context context, SQLiteDatabase db) {
    RunningRecords records = new RunningRecords();
    String[] columns = {
      "_id",
      DB.ACTIVITY.START_TIME,
      DB.ACTIVITY.DISTANCE,
      DB.ACTIVITY.TIME,
      DB.ACTIVITY.NAME,
      DB.ACTIVITY.SPORT
    };
    try (Cursor c = db.query(DB.ACTIVITY.TABLE, columns, "deleted == 0", null, null, null, null)) {
      while (c.moveToNext()) {
        int sport = c.isNull(5) ? DB.ACTIVITY.SPORT_OTHER : c.getInt(5);
        if (!isRunning(sport)) {
          continue;
        }
        long id = c.getLong(0);
        long start = c.getLong(1);
        double distance = c.isNull(2) ? 0 : c.getDouble(2);
        String name = c.getString(4);
        String title =
            TextUtils.isEmpty(name)
                ? HistoryFragment.defaultTitle(
                    context, start, Sport.textOf(context.getResources(), sport))
                : name;

        records.runCount++;
        records.totalDistance += distance;
        records.totalTime += c.isNull(3) ? 0 : c.getLong(3);
        if (distance > 0) {
          records.longestRuns.add(new Entry(id, start, title, distance));
        }
        if (!Sport.isWithoutGps(sport)) {
          records.addTrackRecords(db, id, start, title);
        }
      }
    }

    for (List<Entry> efforts : records.bestEfforts) {
      keepTop(efforts, (a, b) -> Double.compare(a.value, b.value));
    }
    keepTop(records.fastestSplits, (a, b) -> Double.compare(a.value, b.value));
    keepTop(records.longestRuns, (a, b) -> Double.compare(b.value, a.value));
    return records;
  }

  /** Short label of a best effort distance, e.g. "5K" */
  static String effortLabel(Context context, int k) {
    switch (k) {
      case 3:
        return context.getString(org.runnerup.common.R.string.History_half_marathon);
      case 4:
        return context.getString(org.runnerup.common.R.string.History_marathon);
      default:
        return (int) (EFFORT_DISTANCES[k] / 1000) + "K";
    }
  }

  /** The medals of each activity, best rank first */
  LongSparseArray<List<Medal>> medalsByActivity(Context context) {
    LongSparseArray<List<Medal>> medals = new LongSparseArray<>();
    for (int k = 0; k < bestEfforts.size(); k++) {
      addMedals(medals, bestEfforts.get(k), effortLabel(context, k));
    }
    addMedals(
        medals, fastestSplits, context.getString(org.runnerup.common.R.string.History_medal_split));
    addMedals(
        medals, longestRuns, context.getString(org.runnerup.common.R.string.History_medal_longest));
    for (int i = 0; i < medals.size(); i++) {
      Collections.sort(medals.valueAt(i), (a, b) -> Integer.compare(a.rank, b.rank));
    }
    return medals;
  }

  private static void addMedals(
      LongSparseArray<List<Medal>> medals, List<Entry> entries, String label) {
    for (int rank = 0; rank < entries.size(); rank++) {
      long id = entries.get(rank).activityId;
      List<Medal> list = medals.get(id);
      if (list == null) {
        list = new ArrayList<>();
        medals.put(id, list);
      }
      list.add(new Medal(rank, label));
    }
  }

  private static void keepTop(List<Entry> list, Comparator<Entry> order) {
    Collections.sort(list, order);
    while (list.size() > TOP) {
      list.remove(list.size() - 1);
    }
  }

  private void addTrackRecords(SQLiteDatabase db, long id, long start, String title) {
    double[][] track = loadTrack(db, id);
    double[] dist = track[0];
    double[] time = track[1];
    int n = dist.length;
    if (n < 2) {
      return;
    }

    for (int k = 0; k < EFFORT_DISTANCES.length; k++) {
      double best = bestEffort(dist, time, EFFORT_DISTANCES[k]);
      if (best > 0) {
        bestEfforts.get(k).add(new Entry(id, start, title, best));
      }
    }

    double bestSplit = 0;
    double prevTime = 0;
    for (int km = 1; km * SPLIT_DISTANCE <= dist[n - 1]; km++) {
      double t = timeAt(dist, time, km * SPLIT_DISTANCE);
      double split = t - prevTime;
      if (split > 0 && (bestSplit == 0 || split < bestSplit)) {
        bestSplit = split;
      }
      prevTime = t;
    }
    if (bestSplit > 0) {
      fastestSplits.add(new Entry(id, start, title, bestSplit));
    }
  }

  /**
   * Cumulative distance (m) and moving time (s) along the GPS track, leaving out pauses and GPS
   * jumps.
   */
  private static double[][] loadTrack(SQLiteDatabase db, long id) {
    String[] columns = {
      DB.LOCATION.TYPE, DB.LOCATION.TIME, DB.LOCATION.LATITUDE, DB.LOCATION.LONGITUDE
    };
    List<double[]> points = new ArrayList<>();
    try (Cursor c =
        db.query(
            DB.LOCATION.TABLE,
            columns,
            DB.LOCATION.ACTIVITY + " = ?",
            new String[] {Long.toString(id)},
            null,
            null,
            "_id")) {
      double d = 0, t = 0;
      double prevLat = 0, prevLon = 0, prevTime = 0;
      boolean havePrev = false;
      boolean paused = false;
      while (c.moveToNext()) {
        int type = c.getInt(0);
        if (type == DB.LOCATION.TYPE_PAUSE) {
          paused = true;
          havePrev = false;
          continue;
        }
        if (type == DB.LOCATION.TYPE_RESUME) {
          paused = false;
          havePrev = false;
        }
        if (paused || c.isNull(2) || c.isNull(3) || c.isNull(1)) {
          continue;
        }
        double lat = c.getDouble(2);
        double lon = c.getDouble(3);
        double ms = c.getDouble(1);
        if (havePrev) {
          double dd = distance(prevLat, prevLon, lat, lon);
          double dt = (ms - prevTime) / 1000;
          if (dt <= 0 || dd / dt > MAX_SPEED) {
            prevLat = lat;
            prevLon = lon;
            prevTime = ms;
            continue;
          }
          d += dd;
          t += dt;
        }
        points.add(new double[] {d, t});
        prevLat = lat;
        prevLon = lon;
        prevTime = ms;
        havePrev = true;
      }
    }
    double[][] track = new double[2][points.size()];
    for (int i = 0; i < points.size(); i++) {
      track[0][i] = points.get(i)[0];
      track[1][i] = points.get(i)[1];
    }
    return track;
  }

  /**
   * @return the shortest time (s) to cover a distance anywhere in the track, or 0
   */
  static double bestEffort(double[] dist, double[] time, double distance) {
    int n = dist.length;
    if (n < 2 || dist[n - 1] < distance) {
      return 0;
    }
    double best = 0;
    int i = 0;
    for (int j = 1; j < n; j++) {
      // Latest start point i such that i..j still covers the distance
      while (i + 1 < j && dist[j] - dist[i + 1] >= distance) {
        i++;
      }
      if (dist[j] - dist[i] < distance) {
        continue;
      }
      // Start somewhere between point i and i+1, exactly the distance before point j
      double startTime = interpolate(dist, time, i, dist[j] - distance);
      double duration = time[j] - startTime;
      if (duration > 0 && (best == 0 || duration < best)) {
        best = duration;
      }
    }
    return best;
  }

  /** Time (s) at a distance along the track */
  private static double timeAt(double[] dist, double[] time, double distance) {
    int i = 0;
    while (i + 2 < dist.length && dist[i + 1] < distance) {
      i++;
    }
    return interpolate(dist, time, i, distance);
  }

  /** Time at a distance on the segment from point i to i+1 */
  private static double interpolate(double[] dist, double[] time, int i, double distance) {
    double segment = dist[i + 1] - dist[i];
    if (segment <= 0) {
      return time[i];
    }
    double f = Math.max(0, Math.min(1, (distance - dist[i]) / segment));
    return time[i] + f * (time[i + 1] - time[i]);
  }

  private static double distance(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dp = p2 - p1;
    double dl = Math.toRadians(lon2 - lon1);
    double h =
        Math.sin(dp / 2) * Math.sin(dp / 2)
            + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * 6371000 * Math.asin(Math.sqrt(h));
  }
}
