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
import android.text.format.DateFormat;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.runnerup.R;
import org.runnerup.common.util.Constants.DB;
import org.runnerup.util.Formatter;
import org.runnerup.widget.CalendarMonthView;
import org.runnerup.workout.Sport;

/**
 * The "Monthly" page of the history: a calendar of the selected month marking the days with
 * activities, the month's totals and its activities. Tapping a day shows only that day.
 */
final class HistoryMonthlyPage {

  private final Context context;
  private final SQLiteDatabase db;
  private final Formatter formatter;
  private final HistoryFragment.ActivityOpener openActivity;
  private final LayoutInflater inflater;

  private final TextView monthTitle;
  private final CalendarMonthView calendar;
  private final TextView countText;
  private final TextView distanceText;
  private final TextView timeText;
  private final LinearLayout list;

  private int year;
  private int month;
  private int selectedDay = 0;

  HistoryMonthlyPage(
      Context context,
      View root,
      SQLiteDatabase db,
      Formatter formatter,
      HistoryFragment.ActivityOpener openActivity) {
    this.context = context;
    this.db = db;
    this.formatter = formatter;
    this.openActivity = openActivity;
    this.inflater = LayoutInflater.from(context);

    monthTitle = root.findViewById(R.id.history_month_title);
    calendar = root.findViewById(R.id.history_calendar);
    countText = root.findViewById(R.id.history_monthly_count);
    distanceText = root.findViewById(R.id.history_monthly_distance);
    timeText = root.findViewById(R.id.history_monthly_time);
    list = root.findViewById(R.id.history_monthly_list);

    Calendar now = Calendar.getInstance();
    year = now.get(Calendar.YEAR);
    month = now.get(Calendar.MONTH);

    root.findViewById(R.id.history_month_prev).setOnClickListener(v -> moveMonth(-1));
    root.findViewById(R.id.history_month_next).setOnClickListener(v -> moveMonth(1));
    monthTitle.setOnClickListener(v -> pickMonth());
    calendar.setOnDayClickListener(
        day -> {
          selectedDay = day == selectedDay ? 0 : day;
          refresh();
        });
  }

  private void moveMonth(int delta) {
    Calendar c = Calendar.getInstance();
    c.clear();
    c.set(year, month, 1);
    c.add(Calendar.MONTH, delta);
    setMonth(c.get(Calendar.YEAR), c.get(Calendar.MONTH));
  }

  private void setMonth(int year, int month) {
    this.year = year;
    this.month = month;
    selectedDay = 0;
    refresh();
  }

  /** Dialog with month and year wheels */
  private void pickMonth() {
    int currentYear = Calendar.getInstance().get(Calendar.YEAR);
    int firstYear = Math.min(firstActivityYear(currentYear), year);

    NumberPicker monthPicker = new NumberPicker(context);
    monthPicker.setMinValue(0);
    monthPicker.setMaxValue(11);
    monthPicker.setDisplayedValues(new DateFormatSymbols().getShortMonths());
    monthPicker.setValue(month);
    monthPicker.setWrapSelectorWheel(true);

    NumberPicker yearPicker = new NumberPicker(context);
    yearPicker.setMinValue(firstYear);
    yearPicker.setMaxValue(currentYear);
    yearPicker.setValue(year);
    yearPicker.setWrapSelectorWheel(false);

    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.HORIZONTAL);
    layout.setGravity(android.view.Gravity.CENTER);
    int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
    layout.setPadding(pad, pad / 2, pad, 0);
    layout.addView(monthPicker);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMarginStart(pad);
    layout.addView(yearPicker, lp);

    new AlertDialog.Builder(context)
        .setTitle(org.runnerup.common.R.string.History_select_month)
        .setView(layout)
        .setPositiveButton(
            org.runnerup.common.R.string.OK,
            (dialog, which) -> setMonth(yearPicker.getValue(), monthPicker.getValue()))
        .setNegativeButton(org.runnerup.common.R.string.Cancel, null)
        .show();
  }

  private int firstActivityYear(int fallback) {
    try (Cursor c =
        db.rawQuery(
            "select min("
                + DB.ACTIVITY.START_TIME
                + ") from "
                + DB.ACTIVITY.TABLE
                + " where deleted == 0",
            null)) {
      if (c.moveToFirst() && !c.isNull(0)) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(c.getLong(0) * 1000);
        return cal.get(Calendar.YEAR);
      }
    }
    return fallback;
  }

  /** Reloads the shown month from the database */
  void refresh() {
    Calendar c = Calendar.getInstance();
    c.clear();
    c.set(year, month, 1);
    long from = c.getTimeInMillis() / 1000;
    c.add(Calendar.MONTH, 1);
    long to = c.getTimeInMillis() / 1000;

    Locale locale = Locale.getDefault();
    monthTitle.setText(
        DateFormat.format(
            DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), new Date(from * 1000)));

    SparseIntArray dayColors = new SparseIntArray();
    int count = 0;
    double distance = 0;
    long time = 0;
    list.removeAllViews();
    String[] columns = {
      "_id",
      DB.ACTIVITY.START_TIME,
      DB.ACTIVITY.DISTANCE,
      DB.ACTIVITY.TIME,
      DB.ACTIVITY.NAME,
      DB.ACTIVITY.SPORT
    };
    try (Cursor cursor =
        db.query(
            DB.ACTIVITY.TABLE,
            columns,
            "deleted == 0 and "
                + DB.ACTIVITY.START_TIME
                + " >= ? and "
                + DB.ACTIVITY.START_TIME
                + " < ?",
            new String[] {Long.toString(from), Long.toString(to)},
            null,
            null,
            DB.ACTIVITY.START_TIME)) {
      Calendar day = Calendar.getInstance();
      while (cursor.moveToNext()) {
        long id = cursor.getLong(0);
        long start = cursor.getLong(1);
        double d = cursor.isNull(2) ? 0 : cursor.getDouble(2);
        long t = cursor.isNull(3) ? 0 : cursor.getLong(3);
        int sport = cursor.isNull(5) ? DB.ACTIVITY.SPORT_OTHER : cursor.getInt(5);
        day.setTimeInMillis(start * 1000);
        int dayOfMonth = day.get(Calendar.DAY_OF_MONTH);
        if (dayColors.get(dayOfMonth, 0) == 0) {
          dayColors.put(dayOfMonth, ContextCompat.getColor(context, Sport.colorOf(sport)));
        }
        count++;
        distance += d;
        time += t;
        if (selectedDay == 0 || selectedDay == dayOfMonth) {
          list.addView(row(id, start, d, t, cursor.getString(4), sport));
        }
      }
    }

    calendar.setMonth(year, month, dayColors);
    calendar.setSelectedDay(selectedDay);
    countText.setText(String.format(Locale.getDefault(), "%d", count));
    distanceText.setText(formatter.formatDistance(Formatter.Format.TXT_SHORT, (long) distance));
    timeText.setText(formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, time));
  }

  private View row(long id, long start, double distance, long time, String name, int sport) {
    View row = inflater.inflate(R.layout.history_record_row, list, false);
    row.findViewById(R.id.record_badge).setVisibility(View.GONE);
    ImageView icon = row.findViewById(R.id.record_icon);
    icon.setVisibility(View.VISIBLE);
    icon.setImageDrawable(
        AppCompatResources.getDrawable(context, Sport.drawableColored16Of(sport)));
    String sportName = Sport.textOf(context.getResources(), sport);
    ((TextView) row.findViewById(R.id.record_title))
        .setText(
            TextUtils.isEmpty(name)
                ? HistoryFragment.defaultTitle(context, start, sportName)
                : name);
    ((TextView) row.findViewById(R.id.record_subtitle)).setText(formatter.formatDateTime(start));
    String value =
        distance > 0
            ? formatter.formatDistance(Formatter.Format.TXT_SHORT, (long) distance)
            : formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, time);
    ((TextView) row.findViewById(R.id.record_value)).setText(value);
    row.setOnClickListener(v -> openActivity.open(id));
    return row;
  }
}
