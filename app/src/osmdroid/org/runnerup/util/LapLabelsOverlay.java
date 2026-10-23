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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * The lap badges of the activity map, showing the lap values next to the lap number (always, or
 * only for the numbers tapped when the values are listed elsewhere). The labels are placed again on
 * every draw, avoiding earlier labels and the screen edges: next to the lap point (values on the
 * right, else on the left), else moved up or down with a short line to the point (e.g. the way back
 * of an out-and-back run), else only the number.
 */
final class LapLabelsOverlay extends Overlay {

  record Lap(GeoPoint point, Bitmap right, Bitmap left, Bitmap compact) {}

  private final List<Lap> laps;
  private final List<GeoPoint> avoid = new ArrayList<>();
  private final float avoidRadius;
  private final float center;
  private final float margin;
  private final Paint linePaint;
  private final Paint dotPaint;
  private final Paint dotRingPaint;
  private final float dotRadius;

  /** Show the values only for the laps in open (tapped), not for all laps */
  private boolean valuesOnTap = false;

  private final Set<Integer> open = new HashSet<>();

  private final Point pixel = new Point();
  private final List<RectF> placed = new ArrayList<>();
  private final RectF screen = new RectF();

  /**
   * @param center distance from the side of a badge to the center of its number (see {@link
   *     MapMarkers#badgeCenter})
   * @param margin transparent margin around the badges, ignored for overlaps
   * @param density display density, for the line to moved labels
   */
  LapLabelsOverlay(List<Lap> laps, float center, float margin, int lineColor, float density) {
    this.laps = laps;
    this.center = center;
    this.margin = margin;
    linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    linePaint.setColor(lineColor);
    linePaint.setStrokeWidth(2 * density);
    dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dotPaint.setColor(lineColor);
    dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    dotRingPaint.setColor(0xFFFFFFFF);
    dotRadius = 3 * density;
    avoidRadius = 14 * density;
  }

  void setValuesOnTap(boolean valuesOnTap) {
    this.valuesOnTap = valuesOnTap;
  }

  /** Shows the values of a lap (when they are shown on tap only) */
  void open(int index) {
    open.add(index);
  }

  /** Keeps the labels off a marker (start, finish) */
  void avoid(GeoPoint point) {
    avoid.add(point);
  }

  /** Where a badge is drawn, and the lap point it is connected to if moved */
  private record Placement(Bitmap bitmap, float left, float top, float x, float y, boolean moved) {}

  /** Placements of the last draw, for taps */
  private Placement[] lastPlacements = new Placement[0];

  @Override
  public void draw(Canvas canvas, Projection projection) {
    placed.clear();
    screen.set(0, 0, canvas.getWidth(), canvas.getHeight());
    for (GeoPoint point : avoid) {
      projection.toPixels(point, pixel);
      placed.add(
          new RectF(
              pixel.x - avoidRadius,
              pixel.y - avoidRadius,
              pixel.x + avoidRadius,
              pixel.y + avoidRadius));
    }
    // The opened laps first, so their values get the best places
    Placement[] placements = new Placement[laps.size()];
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < laps.size(); i++) {
        boolean showValues = !valuesOnTap || open.contains(i);
        if (showValues == (pass == 0)) {
          Lap lap = laps.get(i);
          projection.toPixels(lap.point, pixel);
          placements[i] = place(lap, pixel.x, pixel.y, showValues);
        }
      }
    }
    lastPlacements = placements;

    // Lines to the moved badges below all badges, then the number only badges below the labels
    for (Placement p : placements) {
      if (p.moved) {
        float badgeY = p.top + p.bitmap.getHeight() / 2f;
        canvas.drawLine(p.x, p.y, p.x, badgeY, linePaint);
        canvas.drawCircle(p.x, p.y, dotRadius + dotRadius / 2, dotRingPaint);
        canvas.drawCircle(p.x, p.y, dotRadius, dotPaint);
      }
    }
    for (int pass = 0; pass < 2; pass++) {
      for (int i = 0; i < placements.length; i++) {
        Placement p = placements[i];
        boolean compact = p.bitmap == laps.get(i).compact;
        if (compact == (pass == 0)) {
          canvas.drawBitmap(p.bitmap, p.left, p.top, null);
        }
      }
    }
  }

  @Override
  public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
    if (!valuesOnTap) {
      return false;
    }
    // Top most badge first
    for (int i = lastPlacements.length - 1; i >= 0; i--) {
      Placement p = lastPlacements[i];
      if (p != null
          && box(p.left, p.top + p.bitmap.getHeight() / 2f, p.bitmap)
              .contains(e.getX(), e.getY())) {
        if (!open.remove(i)) {
          open.add(i);
        }
        mapView.invalidate();
        return true;
      }
    }
    return false;
  }

  private Placement place(Lap lap, float x, float y, boolean showValues) {
    // Vertical moves: none, then up and down by one to three badges (with a small gap)
    float step = lap.compact.getHeight() - margin;
    float[] moves = {0, -step, step, -2 * step, 2 * step, -3 * step, 3 * step};
    for (float dy : moves) {
      for (int side = 0; side < 2; side++) {
        Bitmap bitmap = !showValues ? lap.compact : side == 0 ? lap.right : lap.left;
        float left = side == 0 ? x - center : x + center - bitmap.getWidth();
        RectF box = box(left, y + dy, bitmap);
        if (screen.contains(box) && !overlaps(box)) {
          placed.add(box);
          return new Placement(bitmap, left, y + dy - bitmap.getHeight() / 2f, x, y, dy != 0);
        }
      }
    }
    Bitmap bitmap = lap.compact;
    placed.add(box(x - center, y, bitmap));
    return new Placement(bitmap, x - center, y - bitmap.getHeight() / 2f, x, y, false);
  }

  /** Visible part of a badge drawn at left, centered vertically on y */
  private RectF box(float left, float y, Bitmap bitmap) {
    float top = y - bitmap.getHeight() / 2f;
    return new RectF(
        left + margin,
        top + margin,
        left + bitmap.getWidth() - margin,
        top + bitmap.getHeight() - margin);
  }

  private boolean overlaps(RectF box) {
    for (RectF other : placed) {
      if (RectF.intersects(box, other)) {
        return true;
      }
    }
    return false;
  }
}
