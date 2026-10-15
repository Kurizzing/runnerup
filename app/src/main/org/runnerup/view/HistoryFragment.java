/*
 * Copyright (C) 2012 - 2013 jonas.oreland@gmail.com
 *
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
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.cursoradapter.widget.CursorAdapter;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.Loader;
import androidx.preference.PreferenceManager;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.runnerup.R;
import org.runnerup.common.util.Constants;
import org.runnerup.db.ActivityCleaner;
import org.runnerup.db.DBHelper;
import org.runnerup.db.entities.ActivityEntity;
import org.runnerup.util.Formatter;
import org.runnerup.util.SimpleCursorLoader;
import org.runnerup.widget.RouteView;
import org.runnerup.workout.Sport;

public class HistoryFragment extends Fragment
    implements Constants, OnItemClickListener, LoaderCallbacks<Cursor> {

  private static final int VIEW_COMPACT = 0;
  private static final int VIEW_FEED = 1;
  private static final int MAX_ROUTE_POINTS = 400;
  private static final double[] NO_ROUTE = new double[0];

  private SQLiteDatabase mDB = null;
  private Formatter formatter = null;

  CursorAdapter cursorAdapter = null;
  View fab = null;

  private int viewMode = VIEW_COMPACT;
  private View compactButton = null;
  private View feedButton = null;
  private final LruCache<Long, double[]> routeCache = new LruCache<>(64);
  private final ExecutorService routeExecutor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final ActivityResultLauncher<Intent> manualActivityLauncher =
      registerForActivityResult(
          new StartActivityForResult(),
          result -> LoaderManager.getInstance(this).restartLoader(0, null, this));

  private final ActivityResultLauncher<Intent> detailActivityLauncher =
      registerForActivityResult(
          new StartActivityForResult(),
          result -> {
            // The activity may have been edited or deleted
            routeCache.evictAll();
            LoaderManager.getInstance(this).restartLoader(0, null, this);
          });

  public HistoryFragment() {
    super(R.layout.history);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    ListView listView = view.findViewById(R.id.history_list);
    fab = view.findViewById(R.id.history_add);

    Context context = requireContext();
    fab.setOnClickListener(
        v -> {
          Intent i = new Intent(context, ManualActivity.class);
          manualActivityLauncher.launch(i);
        });

    mDB = DBHelper.getReadableDatabase(context);
    formatter = new Formatter(context);
    listView.setOnItemClickListener(this);
    cursorAdapter = new HistoryListAdapter(context, null);
    listView.setAdapter(cursorAdapter);

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    String viewModeKey = getString(R.string.pref_history_feed_view);
    compactButton = view.findViewById(R.id.history_view_compact);
    feedButton = view.findViewById(R.id.history_view_feed);
    compactButton.setOnClickListener(
        v -> {
          prefs.edit().putBoolean(viewModeKey, false).apply();
          setViewMode(VIEW_COMPACT);
        });
    feedButton.setOnClickListener(
        v -> {
          prefs.edit().putBoolean(viewModeKey, true).apply();
          setViewMode(VIEW_FEED);
        });
    setViewMode(prefs.getBoolean(viewModeKey, false) ? VIEW_FEED : VIEW_COMPACT);

    LoaderManager.getInstance(this).initLoader(0, null, this);
    AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

    new ActivityCleaner().conditionalRecompute(mDB);
  }

  @Override
  public void onResume() {
    super.onResume();
    LoaderManager.getInstance(this).restartLoader(0, null, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    routeExecutor.shutdownNow();
    mainHandler.removeCallbacksAndMessages(null);
    DBHelper.closeDB(mDB);
  }

  @NonNull
  @Override
  public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
    String[] from =
        new String[] {
          "_id",
          DB.ACTIVITY.START_TIME,
          DB.ACTIVITY.DISTANCE,
          DB.ACTIVITY.TIME,
          DB.ACTIVITY.SPORT,
          DB.ACTIVITY.NAME,
          DB.ACTIVITY.COMMENT,
          DB.ACTIVITY.AVG_HR
        };

    return new SimpleCursorLoader(
        requireContext(),
        mDB,
        DB.ACTIVITY.TABLE,
        from,
        "deleted == 0",
        null,
        DB.ACTIVITY.START_TIME + " desc");
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Cursor> arg0, Cursor arg1) {
    cursorAdapter.swapCursor(arg1);
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Cursor> arg0) {
    cursorAdapter.swapCursor(null);
  }

  @Override
  public void onItemClick(AdapterView<?> arg0, View arg1, int position, long id) {
    Intent intent = new Intent(requireContext(), DetailActivity.class);
    intent.putExtra("ID", id);
    intent.putExtra("mode", "details");
    detailActivityLauncher.launch(intent);
  }

  private void setViewMode(int mode) {
    viewMode = mode;
    compactButton.setSelected(mode == VIEW_COMPACT);
    feedButton.setSelected(mode == VIEW_FEED);
    cursorAdapter.notifyDataSetChanged();
  }

  /** GPS track of an activity as interleaved lat/lon, thinned to about MAX_ROUTE_POINTS */
  private double[] loadRoute(long activityId) {
    String[] columns = {DB.LOCATION.LATITUDE, DB.LOCATION.LONGITUDE};
    try (Cursor c =
        mDB.query(
            DB.LOCATION.TABLE,
            columns,
            DB.LOCATION.ACTIVITY + " = ? and " + DB.LOCATION.TYPE + " = ?",
            new String[] {Long.toString(activityId), Integer.toString(DB.LOCATION.TYPE_GPS)},
            null,
            null,
            "_id")) {
      int count = c.getCount();
      if (count < 2) {
        return NO_ROUTE;
      }
      int stride = (count + MAX_ROUTE_POINTS - 1) / MAX_ROUTE_POINTS;
      double[] route = new double[2 * ((count - 1) / stride + 2)];
      int n = 0;
      while (c.moveToNext()) {
        int pos = c.getPosition();
        if (pos % stride == 0 || pos == count - 1) {
          route[n++] = c.getDouble(0);
          route[n++] = c.getDouble(1);
        }
      }
      return n == route.length ? route : Arrays.copyOf(route, n);
    } catch (Exception e) {
      return NO_ROUTE;
    }
  }

  private void showRoute(RouteView routeView, double[] route) {
    routeView.setRoute(route);
    routeView.setVisibility(route.length > 0 ? View.VISIBLE : View.GONE);
  }

  private void bindRoute(RouteView routeView, long activityId) {
    routeView.setTag(activityId);
    double[] cached = routeCache.get(activityId);
    if (cached != null) {
      showRoute(routeView, cached);
      return;
    }
    routeView.setRoute(null);
    routeView.setVisibility(View.VISIBLE);
    routeExecutor.execute(
        () -> {
          double[] route = loadRoute(activityId);
          mainHandler.post(
              () -> {
                routeCache.put(activityId, route);
                if (Long.valueOf(activityId).equals(routeView.getTag())) {
                  showRoute(routeView, route);
                }
              });
        });
  }

  class HistoryListAdapter extends CursorAdapter {
    final LayoutInflater inflater;

    HistoryListAdapter(Context context, Cursor c) {
      super(context, c, true);
      inflater = LayoutInflater.from(context);
    }

    private boolean sameMonthAsPrevious(int curYear, int curMonth, Cursor cursor) {
      int curPosition = cursor.getPosition();
      if (curPosition == 0) return false;

      cursor.moveToPrevious();
      long prevTimeInSecs = new ActivityEntity(cursor).getStartTime();

      Calendar prevCal = Calendar.getInstance();
      prevCal.setTime(new Date(prevTimeInSecs * 1000));
      return prevCal.get(Calendar.YEAR) == curYear && prevCal.get(Calendar.MONTH) == curMonth;
    }

    @Override
    public int getViewTypeCount() {
      return 2;
    }

    @Override
    public int getItemViewType(int position) {
      return viewMode;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
      ActivityEntity ae = new ActivityEntity(cursor);
      if (viewMode == VIEW_FEED) {
        bindFeedView(view, context, cursor, ae);
        return;
      }

      bindSectionTitle(view, cursor, ae);

      TextView dateText = view.findViewById(R.id.history_list_date);
      dateText.setText(formatter.formatDateTime(ae.getStartTime()));

      // distance
      Double d = ae.getDistance();
      TextView distanceText = view.findViewById(R.id.history_list_distance);
      if (d != null) {
        distanceText.setText(formatter.formatDistance(Formatter.Format.TXT_SHORT, d.longValue()));
      } else {
        distanceText.setText("");
      }

      // sport + additional info
      Integer s = ae.getSport();
      ImageView emblem = view.findViewById(R.id.history_list_emblem);
      TextView additionalInfo = view.findViewById(R.id.history_list_additional);

      int sportColor = ContextCompat.getColor(context, Sport.colorOf(s));
      Drawable sportDrawable =
          AppCompatResources.getDrawable(context, Sport.drawableColored16Of(s));
      emblem.setImageDrawable(sportDrawable);
      distanceText.setTextColor(sportColor);
      additionalInfo.setTextColor(sportColor);
      Integer hr = ae.getAvgHr();
      if (hr != null) {
        additionalInfo.setText(formatter.formatHeartRate(Formatter.Format.TXT_SHORT, hr));
      } else {
        additionalInfo.setText(null);
      }

      // duration
      Long dur = ae.getTime();
      TextView durationText = view.findViewById(R.id.history_list_duration);
      if (dur != null) {
        durationText.setText(formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, dur));
      } else {
        durationText.setText("");
      }

      // pace
      TextView paceText = view.findViewById(R.id.history_list_pace);
      String paceTextContents = "";
      if (d != null && dur != null && dur != 0) {
        paceTextContents =
            formatter.formatVelocityByPreferredUnit(Formatter.Format.TXT_LONG, d / dur);
      }
      paceText.setText(paceTextContents);
    }

    private void bindSectionTitle(View view, Cursor cursor, ActivityEntity ae) {
      // month + day
      Date curDate = new Date(ae.getStartTime() * 1000);
      Calendar cal = Calendar.getInstance();
      cal.setTime(curDate);

      TextView sectionTitle = view.findViewById(R.id.history_section_title);
      int year = cal.get(Calendar.YEAR);
      int month = cal.get(Calendar.MONTH);
      if (sameMonthAsPrevious(year, month, cursor)) {
        sectionTitle.setVisibility(View.GONE);
      } else {
        sectionTitle.setVisibility(View.VISIBLE);
        sectionTitle.setText(formatter.formatMonth(curDate));
      }
    }

    /** Strava-like default title, e.g. "Morning Running", when the activity has no name */
    private String defaultTitle(Context context, long startTime, String sportName) {
      Calendar cal = Calendar.getInstance();
      cal.setTime(new Date(startTime * 1000));
      int hour = cal.get(Calendar.HOUR_OF_DAY);
      int res;
      if (hour >= 5 && hour < 12) {
        res = org.runnerup.common.R.string.History_title_morning;
      } else if (hour >= 12 && hour < 17) {
        res = org.runnerup.common.R.string.History_title_afternoon;
      } else if (hour >= 17 && hour < 21) {
        res = org.runnerup.common.R.string.History_title_evening;
      } else {
        res = org.runnerup.common.R.string.History_title_night;
      }
      return context.getString(res, sportName);
    }

    private void setStat(View view, int boxId, int valueId, String value) {
      view.findViewById(boxId).setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
      ((TextView) view.findViewById(valueId)).setText(value);
    }

    private void bindFeedView(View view, Context context, Cursor cursor, ActivityEntity ae) {
      bindSectionTitle(view, cursor, ae);
      view.findViewById(R.id.history_feed_card).setClipToOutline(true);

      Integer s = ae.getSport();
      int sport = s != null ? s : DB.ACTIVITY.SPORT_OTHER;
      String sportName = Sport.textOf(context.getResources(), sport);

      ImageView emblem = view.findViewById(R.id.history_list_emblem);
      emblem.setImageDrawable(
          AppCompatResources.getDrawable(context, Sport.drawableColored16Of(sport)));
      ((TextView) view.findViewById(R.id.history_feed_sport)).setText(sportName);
      ((TextView) view.findViewById(R.id.history_list_date))
          .setText(formatter.formatDateTime(ae.getStartTime()));

      String name = ae.getName();
      ((TextView) view.findViewById(R.id.history_feed_title))
          .setText(
              TextUtils.isEmpty(name) ? defaultTitle(context, ae.getStartTime(), sportName) : name);

      String comment = ae.getComment();
      TextView commentText = view.findViewById(R.id.history_feed_comment);
      commentText.setVisibility(TextUtils.isEmpty(comment) ? View.GONE : View.VISIBLE);
      commentText.setText(comment);

      Double d = ae.getDistance();
      Long dur = ae.getTime();
      Integer hr = ae.getAvgHr();
      setStat(
          view,
          R.id.history_feed_distance_box,
          R.id.history_feed_distance,
          d != null && d > 0
              ? formatter.formatDistance(Formatter.Format.TXT_SHORT, d.longValue())
              : null);
      setStat(
          view,
          R.id.history_feed_duration_box,
          R.id.history_feed_duration,
          dur != null ? formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, dur) : null);
      ((TextView) view.findViewById(R.id.history_feed_pace_label))
          .setText(formatter.formatVelocityLabel());
      setStat(
          view,
          R.id.history_feed_pace_box,
          R.id.history_feed_pace,
          d != null && d > 0 && dur != null && dur != 0
              ? formatter.formatVelocityByPreferredUnit(Formatter.Format.TXT_LONG, d / dur)
              : null);
      setStat(
          view,
          R.id.history_feed_hr_box,
          R.id.history_feed_hr,
          hr != null ? formatter.formatHeartRate(Formatter.Format.TXT_SHORT, hr) : null);

      RouteView routeView = view.findViewById(R.id.history_feed_route);
      routeView.setRouteColor(ContextCompat.getColor(context, Sport.colorOf(sport)));
      if (Sport.isWithoutGps(sport)) {
        routeView.setTag(null);
        routeView.setVisibility(View.GONE);
      } else {
        bindRoute(routeView, ae.getId());
      }
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
      int layout = viewMode == VIEW_FEED ? R.layout.history_feed_row : R.layout.history_row;
      return inflater.inflate(layout, parent, false);
    }
  }
}
