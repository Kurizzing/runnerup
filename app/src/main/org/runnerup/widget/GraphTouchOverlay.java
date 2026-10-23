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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.core.content.ContextCompat;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import java.util.List;
import org.runnerup.R;

/**
 * Drawn on top of a graph: marks the highest and lowest points with their values, and shows the x
 * and y values where the graph is touched (drag sideways to follow the line).
 */
// Only created from code, for a given graph
@SuppressLint("ViewConstructor")
public class GraphTouchOverlay extends View {

  public interface ValueFormat {
    String format(double value);
  }

  private final GraphView graph;
  private final List<DataPoint> points;
  private final ValueFormat xFormat;
  private final ValueFormat yFormat;
  private final String maxLabel;
  private final String minLabel;
  private final int maxIndex;
  private final int minIndex;

  private final float density;
  private final int touchSlop;
  private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();

  /** Touched point, -1 if none */
  private int selected = -1;

  private float downX;
  private float downY;
  private boolean dragging;

  /**
   * @param maxLabel label of the highest point, e.g. "Max" (or "Fastest" for a pace graph)
   */
  public GraphTouchOverlay(
      Context context,
      GraphView graph,
      List<DataPoint> points,
      ValueFormat xFormat,
      ValueFormat yFormat,
      String maxLabel,
      String minLabel) {
    super(context);
    this.graph = graph;
    this.points = points;
    this.xFormat = xFormat;
    this.yFormat = yFormat;
    this.maxLabel = maxLabel;
    this.minLabel = minLabel;

    int max = -1;
    int min = -1;
    for (int i = 0; i < points.size(); i++) {
      double y = points.get(i).getY();
      if (max < 0 || y > points.get(max).getY()) {
        max = i;
      }
      if (min < 0 || y < points.get(min).getY()) {
        min = i;
      }
    }
    maxIndex = max;
    minIndex = min;

    density = context.getResources().getDisplayMetrics().density;
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    int accent = ContextCompat.getColor(context, R.color.colorAccent);
    int text = ContextCompat.getColor(context, R.color.colorText);
    linePaint.setColor(text);
    linePaint.setAlpha(140);
    linePaint.setStrokeWidth(density);
    dotPaint.setColor(accent);
    ringPaint.setColor(0xFFFFFFFF);
    bubblePaint.setColor(ContextCompat.getColor(context, R.color.dialogBackground));
    bubbleStroke.setColor(ContextCompat.getColor(context, R.color.cardStroke));
    bubbleStroke.setStyle(Paint.Style.STROKE);
    bubbleStroke.setStrokeWidth(density);
    valuePaint.setColor(text);
    valuePaint.setTextSize(13 * density);
    valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
    captionPaint.setColor(ContextCompat.getColor(context, R.color.colorTextSecondary));
    captionPaint.setTextSize(11 * density);
  }

  private float toPixelX(double x) {
    Viewport v = graph.getViewport();
    double minX = v.getMinX(false);
    double maxX = v.getMaxX(false);
    double f = maxX > minX ? (x - minX) / (maxX - minX) : 0;
    return (float) (graph.getGraphContentLeft() + f * graph.getGraphContentWidth());
  }

  private float toPixelY(double y) {
    Viewport v = graph.getViewport();
    double minY = v.getMinY(false);
    double maxY = v.getMaxY(false);
    double f = maxY > minY ? (y - minY) / (maxY - minY) : 0;
    return (float) (graph.getGraphContentTop() + graph.getGraphContentHeight() * (1 - f));
  }

  /** Index of the point closest to the pixel x */
  private int indexAt(float px) {
    Viewport v = graph.getViewport();
    double minX = v.getMinX(false);
    double maxX = v.getMaxX(false);
    double x =
        minX + (px - graph.getGraphContentLeft()) / graph.getGraphContentWidth() * (maxX - minX);
    int lo = 0;
    int hi = points.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi) / 2;
      if (points.get(mid).getX() < x) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    if (lo > 0 && x - points.get(lo - 1).getX() < points.get(lo).getX() - x) {
      lo--;
    }
    return lo;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (points.isEmpty() || graph.getGraphContentWidth() <= 0) {
      return;
    }
    if (minIndex != maxIndex) {
      drawExtreme(canvas, maxIndex, maxLabel, true);
      drawExtreme(canvas, minIndex, minLabel, false);
    }
    if (selected >= 0) {
      drawSelection(canvas, points.get(selected));
    }
  }

  /** Dot on the highest (or lowest) point, with its value above (or below) it */
  private void drawExtreme(Canvas canvas, int index, String label, boolean above) {
    DataPoint p = points.get(index);
    float x = toPixelX(p.getX());
    float y = toPixelY(p.getY());
    drawDot(canvas, x, y);

    String value = label + "  " + yFormat.format(p.getY());
    float width = valuePaint.measureText(value) + 16 * density;
    float height = 22 * density;
    float gap = 8 * density;
    float top = above ? y - gap - height : y + gap;
    // Inside the graph: below the highest point if there is no room above it, and so on
    if (top < graph.getGraphContentTop()) {
      top = y + gap;
    } else if (top + height > graph.getGraphContentTop() + graph.getGraphContentHeight()) {
      top = y - gap - height;
    }
    float left = clampLeft(x - width / 2, width);
    rect.set(left, top, left + width, top + height);
    drawBubble(canvas);
    canvas.drawText(value, left + 8 * density, baseline(valuePaint, rect.centerY()), valuePaint);
  }

  /** Vertical line at the touched point, with its x and y values at the top */
  private void drawSelection(Canvas canvas, DataPoint p) {
    float x = toPixelX(p.getX());
    float y = toPixelY(p.getY());
    float top = graph.getGraphContentTop();
    canvas.drawLine(x, top, x, top + graph.getGraphContentHeight(), linePaint);
    drawDot(canvas, x, y);

    String xText = xFormat.format(p.getX());
    String yText = yFormat.format(p.getY());
    float width =
        Math.max(captionPaint.measureText(xText), valuePaint.measureText(yText)) + 20 * density;
    float height = 40 * density;
    float left = clampLeft(x - width / 2, width);
    rect.set(left, top + 4 * density, left + width, top + 4 * density + height);
    drawBubble(canvas);
    float textX = left + 10 * density;
    canvas.drawText(xText, textX, rect.top + 15 * density, captionPaint);
    canvas.drawText(yText, textX, rect.top + 32 * density, valuePaint);
  }

  private void drawDot(Canvas canvas, float x, float y) {
    canvas.drawCircle(x, y, 5.5f * density, ringPaint);
    canvas.drawCircle(x, y, 4 * density, dotPaint);
  }

  private void drawBubble(Canvas canvas) {
    float r = 8 * density;
    canvas.drawRoundRect(rect, r, r, bubblePaint);
    canvas.drawRoundRect(rect, r, r, bubbleStroke);
  }

  /** Left of a bubble of the width, kept inside the graph area */
  private float clampLeft(float left, float width) {
    float min = graph.getGraphContentLeft();
    float max = graph.getGraphContentLeft() + graph.getGraphContentWidth() - width;
    return Math.max(min, Math.min(max, left));
  }

  private static float baseline(Paint paint, float centerY) {
    Paint.FontMetrics fm = paint.getFontMetrics();
    return centerY - (fm.ascent + fm.descent) / 2;
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (points.isEmpty()) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downX = event.getX();
        downY = event.getY();
        dragging = false;
        select(event.getX());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!dragging) {
          float dx = Math.abs(event.getX() - downX);
          float dy = Math.abs(event.getY() - downY);
          if (dx > touchSlop && dx >= dy) {
            // Sideways: follow the line, the page must not scroll
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
          }
        }
        if (dragging) {
          select(event.getX());
        }
        return true;
      case MotionEvent.ACTION_CANCEL:
        // The page scrolled: the touch was not meant for the graph
        selected = -1;
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return true;
    }
  }

  private void select(float x) {
    int index = indexAt(x);
    if (index != selected) {
      selected = index;
      invalidate();
    }
  }
}
