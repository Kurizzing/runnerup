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

package org.runnerup.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import org.runnerup.R;

/**
 * Marker icons of the activity map: start and finish dots, and lap badges (dark circle with the lap
 * number, or a pill with the lap distance and time once tapped).
 */
final class MapMarkers {

  private static final int SHADOW_COLOR = 0x66000000;

  private final Resources res;
  private final float density;
  private final int white;
  private final int dark;
  private final int accent;
  private final int green;
  private final int red;

  MapMarkers(Context context) {
    res = context.getResources();
    density = res.getDisplayMetrics().density;
    white = 0xFFFFFFFF;
    dark = ContextCompat.getColor(context, R.color.dialogBackground);
    accent = ContextCompat.getColor(context, R.color.colorAccent);
    green = ContextCompat.getColor(context, R.color.buttonGreen);
    red = ContextCompat.getColor(context, R.color.buttonRed);
  }

  Drawable start() {
    return dot(green);
  }

  Drawable finish() {
    return dot(red);
  }

  /** Start and finish at the same place: red dot inside a green ring */
  Drawable startFinish() {
    float radius = dp(11);
    float margin = dp(4);
    int size = Math.round(2 * (radius + margin));
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    float c = size / 2f;
    Paint paint = fill(white);
    paint.setShadowLayer(dp(2), 0, dp(1), SHADOW_COLOR);
    canvas.drawCircle(c, c, radius, paint);
    canvas.drawCircle(c, c, radius - dp(2), fill(green));
    canvas.drawCircle(c, c, radius - dp(5), fill(white));
    canvas.drawCircle(c, c, radius - dp(7), fill(red));
    return new BitmapDrawable(res, bitmap);
  }

  /** Colored dot with a white ring */
  private Drawable dot(int color) {
    float radius = dp(8);
    float ring = dp(3);
    float margin = dp(4);
    int size = Math.round(2 * (radius + margin));
    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    float c = size / 2f;
    Paint paint = fill(white);
    paint.setShadowLayer(dp(2), 0, dp(1), SHADOW_COLOR);
    canvas.drawCircle(c, c, radius, paint);
    canvas.drawCircle(c, c, radius - ring, fill(color));
    return new BitmapDrawable(res, bitmap);
  }

  /**
   * Lap badge: the lap number in a circle, with the details in a pill next to it. The circle is
   * centered vertically and {@link #badgeCenter} from the side it is on.
   *
   * @param details null for the number only
   * @param numberOnRight true to put the details on the left of the number
   * @param color color of the number, null for the default style (dark badge, accent circle)
   */
  Bitmap lap(
      String number, @Nullable String details, boolean numberOnRight, @Nullable Integer color) {
    float height = dp(24);
    float margin = dp(4);
    float ring = dp(2);

    Paint numberPaint = text(dp(11));
    int circleColor = color != null ? color : details != null ? accent : dark;
    numberPaint.setColor(textOn(circleColor));
    Paint detailsPaint = text(dp(12));
    float detailsWidth = details == null ? 0 : dp(8) + detailsPaint.measureText(details) + dp(10);

    int width = Math.round(height + detailsWidth + 2 * margin);
    int bitmapHeight = Math.round(height + 2 * margin);
    Bitmap bitmap = Bitmap.createBitmap(width, bitmapHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);

    // White outline with a soft shadow, then the dark body
    RectF outer = new RectF(margin, margin, width - margin, margin + height);
    float r = height / 2;
    Paint outline = fill(white);
    outline.setShadowLayer(dp(2), 0, dp(1), SHADOW_COLOR);
    canvas.drawRoundRect(outer, r, r, outline);
    RectF inner = new RectF(outer);
    inner.inset(ring, ring);
    canvas.drawRoundRect(inner, r - ring, r - ring, fill(dark));

    // Number: in an accent circle when the details are shown
    float cx = numberOnRight ? width - margin - r : margin + r;
    float cy = margin + r;
    if (details != null || color != null) {
      canvas.drawCircle(cx, cy, r - ring, fill(circleColor));
    }
    canvas.drawText(number, cx, baseline(numberPaint, cy), numberPaint);

    if (details != null) {
      detailsPaint.setTextAlign(Paint.Align.LEFT);
      float x = numberOnRight ? margin + dp(10) : margin + height + dp(8);
      canvas.drawText(details, x, baseline(detailsPaint, cy), detailsPaint);
    }
    return bitmap;
  }

  /**
   * @return the color at t (0..1) between start and end, going around the color wheel the short way
   *     (e.g. green, yellow, red) so the steps stay bright
   */
  static int gradient(int start, int end, float t) {
    float[] a = new float[3];
    float[] b = new float[3];
    Color.colorToHSV(start, a);
    Color.colorToHSV(end, b);
    float hue = b[0] - a[0];
    if (hue > 180) {
      hue -= 360;
    } else if (hue < -180) {
      hue += 360;
    }
    float[] hsv = {
      (a[0] + hue * t + 360) % 360, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t
    };
    return Color.HSVToColor(hsv);
  }

  /**
   * @return white or dark text, whichever is readable on the color
   */
  int textOn(int color) {
    double luminance =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
    return luminance > 0.7 ? dark : white;
  }

  /**
   * @return distance from the side of a lap badge to the center of its number circle
   */
  float badgeCenter() {
    return dp(4) + dp(12);
  }

  /**
   * @return transparent margin around the badges (shadow), ignored when checking overlaps
   */
  float badgeMargin() {
    return dp(4);
  }

  private Paint fill(int color) {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
    return paint;
  }

  private Paint text(float size) {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(white);
    paint.setTextSize(size);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.DEFAULT_BOLD);
    return paint;
  }

  /** Baseline that centers the text vertically on centerY */
  private static float baseline(Paint paint, float centerY) {
    Paint.FontMetrics fm = paint.getFontMetrics();
    return centerY - (fm.ascent + fm.descent) / 2;
  }

  private float dp(float v) {
    return v * density;
  }
}
