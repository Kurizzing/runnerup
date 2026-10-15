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

package org.runnerup.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import org.runnerup.R;
import org.runnerup.workout.Sport;

/**
 * Sports in display order ({@link Sport#getDisplayOrder}), each shown with its icon. Position i is
 * sport {@link #getSport(int)}; spinners map positions to DB values with {@code
 * runnerup:values="@array/sport_order_values"}.
 */
public class SportAdapter extends ArrayAdapter<CharSequence> {

  private final int[] order;
  private final int iconSizeDp;

  /**
   * @param layout layout of the selected item (a TextView)
   * @param iconSizeDp icon size of the selected item; drop-down rows use 22dp
   */
  public SportAdapter(@NonNull Context context, int layout, int iconSizeDp) {
    this(context, layout, iconSizeDp, Sport.getDisplayOrder(context.getResources()));
  }

  private SportAdapter(Context context, int layout, int iconSizeDp, int[] order) {
    super(context, layout, sportNames(context, order));
    this.order = order;
    this.iconSizeDp = iconSizeDp;
    setDropDownViewResource(R.layout.dropdown_item);
  }

  private static CharSequence[] sportNames(Context context, int[] order) {
    String[] names = Sport.getStringArray(context.getResources());
    CharSequence[] ordered = new CharSequence[order.length];
    for (int i = 0; i < order.length; i++) {
      ordered[i] = names[order[i]];
    }
    return ordered;
  }

  /**
   * @return the sport DB value at a position
   */
  public int getSport(int position) {
    return order[position];
  }

  /**
   * @return the position of a sport DB value, or -1
   */
  public int getPosition(int sport) {
    for (int i = 0; i < order.length; i++) {
      if (order[i] == sport) {
        return i;
      }
    }
    return -1;
  }

  @NonNull
  @Override
  public View getView(int position, View convertView, @NonNull ViewGroup parent) {
    TextView v = (TextView) super.getView(position, convertView, parent);
    setSportIcon(v, order[position], iconSizeDp);
    return v;
  }

  @Override
  public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
    TextView v = (TextView) super.getDropDownView(position, convertView, parent);
    setSportIcon(v, order[position], 22);
    return v;
  }

  /** Shows the sport icon (same as in the history list) before the text */
  public static void setSportIcon(TextView view, int sport, int sizeDp) {
    float density = view.getResources().getDisplayMetrics().density;
    Drawable icon =
        AppCompatResources.getDrawable(view.getContext(), Sport.drawableColored16Of(sport));
    if (icon != null) {
      int size = Math.round(sizeDp * density);
      icon.setBounds(0, 0, size, size);
    }
    view.setCompoundDrawablesRelative(icon, null, null, null);
    view.setCompoundDrawablePadding(Math.round(14 * density));
  }
}
