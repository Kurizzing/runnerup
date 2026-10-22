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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.runnerup.R;
import org.runnerup.db.Shoes;
import org.runnerup.util.Formatter;

/** Row of a shoe (settings and medals page): name, badge, purchase details and distance */
final class ShoeViews {

  private ShoeViews() {}

  /**
   * @param withNotes false to leave out the notes (compact summary)
   */
  static View row(
      LayoutInflater inflater,
      ViewGroup parent,
      Shoes.Shoe shoe,
      boolean isDefault,
      boolean withNotes) {
    Context context = parent.getContext();
    View row = inflater.inflate(R.layout.shoe_row, parent, false);
    ((TextView) row.findViewById(R.id.shoe_name)).setText(shoe.name);

    TextView badge = row.findViewById(R.id.shoe_badge);
    if (shoe.retired) {
      badge.setVisibility(View.VISIBLE);
      badge.setText(org.runnerup.common.R.string.Shoe_retired);
      badge.setTextColor(ContextCompat.getColor(context, R.color.colorTextMuted));
    } else if (isDefault) {
      badge.setVisibility(View.VISIBLE);
      badge.setText(org.runnerup.common.R.string.Shoe_default_badge);
    }

    ((TextView) row.findViewById(R.id.shoe_distance)).setText(distance(context, shoe.distance));

    // "Size 270 · 3/1/26 · ₩159,000", leaving out what is not set
    List<String> details = new ArrayList<>();
    if (!TextUtils.isEmpty(shoe.size)) {
      details.add(context.getString(org.runnerup.common.R.string.Shoe_size_value, shoe.size));
    }
    if (shoe.purchaseDate != 0) {
      details.add(date(context, shoe.purchaseDate));
    }
    if (shoe.price != null) {
      details.add(price(shoe.price));
    }
    show(row.findViewById(R.id.shoe_details), TextUtils.join(" · ", details));
    show(row.findViewById(R.id.shoe_notes), withNotes ? shoe.notes : null);

    ImageView icon = row.findViewById(R.id.shoe_icon);
    icon.setImageTintList(
        ColorStateList.valueOf(
            ContextCompat.getColor(
                context, shoe.retired ? R.color.colorTextMuted : R.color.colorText)));
    return row;
  }

  private static void show(TextView view, String text) {
    view.setText(text);
    view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
  }

  /** Purchase date (seconds since epoch) in the short date format of the locale */
  static String date(Context context, long seconds) {
    return android.text.format.DateFormat.getDateFormat(context).format(new Date(seconds * 1000));
  }

  /** Price in the currency of the locale, without decimals when it is a whole amount */
  static String price(double price) {
    NumberFormat format = NumberFormat.getCurrencyInstance();
    if (price == Math.floor(price)) {
      format.setMaximumFractionDigits(0);
    }
    return format.format(price);
  }

  /** Distance in the preferred unit, e.g. "312 km" or "42.5 km" */
  static String distance(Context context, double meters) {
    double unit = unitMeters(context);
    double value = meters / unit;
    String unitName =
        context.getString(
            unit == Formatter.km_meters
                ? org.runnerup.common.R.string.metrics_distance_km
                : org.runnerup.common.R.string.metrics_distance_mi);
    return String.format(
        Locale.getDefault(), value >= 100 ? "%.0f %s" : "%.1f %s", value, unitName);
  }

  /** Meters per unit of the preferred distance unit (km or mi) */
  static double unitMeters(Context context) {
    return Formatter.getUnitMeters(
        context.getResources(), PreferenceManager.getDefaultSharedPreferences(context));
  }
}
