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
import android.content.res.ColorStateList;
import android.database.sqlite.SQLiteDatabase;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.runnerup.R;
import org.runnerup.db.Shoes;
import org.runnerup.util.Formatter;

/** The "Medals" page of the history: all-time running totals and top 3 records. */
final class HistoryMedalsPage {

  static final int[] MEDAL_COLORS = {0xFFFFD54F, 0xFFCFD8DC, 0xFFE0A16E};

  private final Context context;
  private final LinearLayout content;
  private final SQLiteDatabase mDB;
  private final Formatter formatter;
  private final HistoryFragment.ActivityOpener openActivity;
  private final LayoutInflater inflater;

  HistoryMedalsPage(
      Context context,
      LinearLayout content,
      SQLiteDatabase db,
      Formatter formatter,
      HistoryFragment.ActivityOpener openActivity) {
    this.context = context;
    this.content = content;
    this.mDB = db;
    this.formatter = formatter;
    this.openActivity = openActivity;
    this.inflater = LayoutInflater.from(context);
  }

  /** Shown until the records are computed */
  void showCalculating() {
    content.removeAllViews();
    content.addView(
        sectionTitle(context.getString(org.runnerup.common.R.string.History_calculating)));
  }

  void show(RunningRecords r) {
    content.removeAllViews();

    content.addView(
        sectionTitle(context.getString(org.runnerup.common.R.string.History_all_time_running)));
    LinearLayout totals = card(LinearLayout.HORIZONTAL);
    totals.setPadding(dp(16), dp(16), dp(16), dp(16));
    totals.setDividerDrawable(
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.divider_vertical));
    totals.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    totals.addView(
        stat(
            context.getString(org.runnerup.common.R.string.History_total_distance),
            formatter.formatDistance(Formatter.Format.TXT_SHORT, (long) r.totalDistance),
            0));
    totals.addView(
        stat(
            context.getString(org.runnerup.common.R.string.History_total_time),
            totalTime(r.totalTime),
            dp(12)));
    totals.addView(
        stat(
            context.getString(org.runnerup.common.R.string.History_runs),
            String.format(Locale.getDefault(), "%d", r.runCount),
            dp(12)));
    content.addView(totals);

    boolean anyTrack = !r.fastestSplits.isEmpty();
    if (!anyTrack) {
      TextView empty =
          sectionTitle(context.getString(org.runnerup.common.R.string.History_no_runs));
      content.addView(empty);
    }

    if (anyTrack) {
      content.addView(
          sectionTitle(context.getString(org.runnerup.common.R.string.History_best_efforts)));
      for (int k = 0; k < RunningRecords.EFFORT_DISTANCES.length; k++) {
        List<RunningRecords.Entry> efforts = r.bestEfforts.get(k);
        if (efforts.isEmpty()) {
          continue;
        }
        double distance = RunningRecords.EFFORT_DISTANCES[k];
        LinearLayout card = card(LinearLayout.VERTICAL);
        card.addView(cardHeader(RunningRecords.effortLabel(context, k)));
        for (int i = 0; i < efforts.size(); i++) {
          RunningRecords.Entry e = efforts.get(i);
          card.addView(row(i, e, time(e.value), pace(distance, e.value)));
        }
        content.addView(card);
      }

      content.addView(
          sectionTitle(context.getString(org.runnerup.common.R.string.History_fastest_splits)));
      LinearLayout splits = card(LinearLayout.VERTICAL);
      for (int i = 0; i < r.fastestSplits.size(); i++) {
        RunningRecords.Entry e = r.fastestSplits.get(i);
        splits.addView(row(i, e, time(e.value), pace(1000, e.value)));
      }
      content.addView(splits);
    }

    if (!r.longestRuns.isEmpty()) {
      content.addView(
          sectionTitle(context.getString(org.runnerup.common.R.string.History_longest_runs)));
      LinearLayout longest = card(LinearLayout.VERTICAL);
      for (int i = 0; i < r.longestRuns.size(); i++) {
        RunningRecords.Entry e = r.longestRuns.get(i);
        longest.addView(
            row(i, e, formatter.formatDistance(Formatter.Format.TXT_SHORT, (long) e.value), ""));
      }
      content.addView(longest);
    }

    showShoes();
  }

  /** "My shoes": total distance of each active shoe */
  private void showShoes() {
    content.addView(sectionTitle(context.getString(org.runnerup.common.R.string.My_shoes)));
    LinearLayout card = card(LinearLayout.VERTICAL);
    List<Shoes.Shoe> shoes = Shoes.list(mDB, false);
    if (shoes.isEmpty()) {
      TextView hint = new TextView(context);
      TextViewCompat.setTextAppearance(hint, R.style.HistoryMeta);
      hint.setText(org.runnerup.common.R.string.Shoes_hint);
      hint.setPadding(dp(16), dp(16), dp(16), dp(16));
      card.addView(hint);
    } else {
      long defaultShoe = Shoes.getDefault(context);
      for (Shoes.Shoe shoe : shoes) {
        card.addView(ShoeViews.row(inflater, card, shoe, shoe.id == defaultShoe, false));
      }
    }
    content.addView(card);
  }

  private String time(double seconds) {
    return formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, Math.round(seconds));
  }

  private String pace(double meters, double seconds) {
    return formatter.formatVelocityByPreferredUnit(Formatter.Format.TXT_LONG, meters / seconds);
  }

  /** Total time as hours and minutes, e.g. "35h 12m" */
  private static String totalTime(long seconds) {
    long minutes = seconds / 60;
    return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
  }

  private View row(int rank, RunningRecords.Entry e, String title, String value) {
    View row = inflater.inflate(R.layout.history_record_row, content, false);
    TextView badge = row.findViewById(R.id.record_badge);
    badge.setText(String.format(Locale.getDefault(), "%d", rank + 1));
    badge.setBackgroundTintList(ColorStateList.valueOf(MEDAL_COLORS[rank]));
    ((TextView) row.findViewById(R.id.record_title)).setText(title);
    ((TextView) row.findViewById(R.id.record_value)).setText(value);
    String date =
        android.text.format.DateFormat.getDateFormat(context).format(new Date(e.startTime * 1000));
    ((TextView) row.findViewById(R.id.record_subtitle))
        .setText(context.getString(R.string.History_record_subtitle, e.title, date));
    row.setOnClickListener(v -> openActivity.open(e.activityId));
    return row;
  }

  private LinearLayout card(int orientation) {
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(orientation);
    card.setBackgroundResource(R.drawable.card_background);
    card.setClipToOutline(true);
    card.setBaselineAligned(false);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(10);
    card.setLayoutParams(lp);
    return card;
  }

  private TextView cardHeader(String text) {
    TextView header = new TextView(context);
    TextViewCompat.setTextAppearance(header, R.style.HistoryTitle);
    header.setText(text);
    header.setPadding(dp(16), dp(14), dp(16), dp(4));
    return header;
  }

  private TextView sectionTitle(String text) {
    TextView title = new TextView(context);
    TextViewCompat.setTextAppearance(title, R.style.HistoryMonth);
    title.setText(text);
    title.setPadding(dp(4), dp(16), dp(4), dp(8));
    return title;
  }

  private View stat(String label, String value, int paddingStart) {
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPaddingRelative(paddingStart, 0, dp(4), 0);
    box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    TextView l = new TextView(context);
    TextViewCompat.setTextAppearance(l, R.style.HistoryStatLabel);
    l.setText(label);
    TextView v = new TextView(context);
    TextViewCompat.setTextAppearance(v, R.style.HistoryStatValue);
    v.setText(value);
    v.setMaxLines(1);
    box.addView(l);
    box.addView(v);
    return box;
  }

  private int dp(int v) {
    return Math.round(v * context.getResources().getDisplayMetrics().density);
  }
}
