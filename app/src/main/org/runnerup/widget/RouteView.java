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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import org.runnerup.BuildConfig;
import org.runnerup.R;

/**
 * Route preview: draws a GPS track over OpenStreetMap tiles (dimmed to fit the dark theme), or over
 * a map-like grid when maps are disabled or tiles are not (yet) available.
 */
public class RouteView extends View {

  private static final int TILE_SIZE = 256;
  private static final int MIN_ZOOM = 1;
  private static final int MAX_ZOOM = 17;
  private static final boolean MAP_TILES_ENABLED =
      BuildConfig.OSMDROID_ENABLED || BuildConfig.MAPBOX_ENABLED;

  private final float density;
  private final Paint gridPaint = new Paint();
  private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint attributionBgPaint = new Paint();
  private final Path path = new Path();
  private final RectF tileRect = new RectF();
  private final int backgroundColor;
  private final String attribution;
  private final Runnable onTileLoaded = this::invalidate;

  /** Interleaved latitude/longitude pairs */
  private double[] route = null;

  private boolean pathValid = false;
  private float startX, startY, endX, endY;

  // Mapping of Web Mercator world coordinates (0..1) to view pixels
  private double minWorldX, minWorldY, scale;
  private float offX, offY;
  private int zoom;

  public RouteView(Context context) {
    this(context, null);
  }

  public RouteView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    density = getResources().getDisplayMetrics().density;
    backgroundColor = ContextCompat.getColor(context, R.color.historyMapBg);
    attribution = context.getString(R.string.History_map_attribution);

    gridPaint.setColor(ContextCompat.getColor(context, R.color.historyMapGrid));
    gridPaint.setStrokeWidth(density);

    // Dark map: desaturate, invert and dim the standard (light) OSM tiles
    ColorMatrix matrix = new ColorMatrix();
    matrix.setSaturation(0.3f);
    matrix.postConcat(
        new ColorMatrix(
            new float[] {
              -1, 0, 0, 0, 255,
              0, -1, 0, 0, 255,
              0, 0, -1, 0, 255,
              0, 0, 0, 1, 0,
            }));
    ColorMatrix dim = new ColorMatrix();
    dim.setScale(0.62f, 0.66f, 0.72f, 1f);
    matrix.postConcat(dim);
    tilePaint.setColorFilter(new ColorMatrixColorFilter(matrix));

    shadowPaint.setStyle(Paint.Style.STROKE);
    shadowPaint.setStrokeWidth(7 * density);
    shadowPaint.setStrokeCap(Paint.Cap.ROUND);
    shadowPaint.setStrokeJoin(Paint.Join.ROUND);
    shadowPaint.setColor(0x66000000);

    routePaint.setStyle(Paint.Style.STROKE);
    routePaint.setStrokeWidth(3.5f * density);
    routePaint.setStrokeCap(Paint.Cap.ROUND);
    routePaint.setStrokeJoin(Paint.Join.ROUND);
    routePaint.setColor(ContextCompat.getColor(context, R.color.colorAccent));

    attributionPaint.setColor(0xCCFFFFFF);
    attributionPaint.setTextSize(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 9, getResources().getDisplayMetrics()));
    attributionBgPaint.setColor(0x88000000);
  }

  public void setRouteColor(int color) {
    routePaint.setColor(color);
    invalidate();
  }

  /**
   * @param latLon interleaved latitude/longitude pairs, or null to clear
   */
  public void setRoute(@Nullable double[] latLon) {
    route = latLon;
    pathValid = false;
    invalidate();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    pathValid = false;
  }

  private static double worldX(double lon) {
    return (lon + 180) / 360;
  }

  private static double worldY(double lat) {
    double sin = Math.sin(Math.toRadians(Math.max(-85, Math.min(85, lat))));
    return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  }

  private float toViewX(double wx) {
    return offX + (float) ((wx - minWorldX) * scale);
  }

  private float toViewY(double wy) {
    return offY + (float) ((wy - minWorldY) * scale);
  }

  private void buildPath() {
    path.reset();
    pathValid = true;
    if (route == null || route.length < 4 || getWidth() == 0 || getHeight() == 0) {
      return;
    }

    double maxWorldX = -1, maxWorldY = -1;
    minWorldX = 2;
    minWorldY = 2;
    for (int i = 0; i < route.length; i += 2) {
      double wx = worldX(route[i + 1]);
      double wy = worldY(route[i]);
      minWorldX = Math.min(minWorldX, wx);
      maxWorldX = Math.max(maxWorldX, wx);
      minWorldY = Math.min(minWorldY, wy);
      maxWorldY = Math.max(maxWorldY, wy);
    }
    double spanX = Math.max(maxWorldX - minWorldX, 1e-9);
    double spanY = Math.max(maxWorldY - minWorldY, 1e-9);

    float pad = 24 * density;
    float w = getWidth() - 2 * pad;
    float h = getHeight() - 2 * pad;
    scale = Math.min(w / spanX, h / spanY);
    // Pick the zoom where a tile is drawn at roughly 1x-2x of 128dp, keeping map labels legible
    double tilesAcrossWorld = scale / (TILE_SIZE / 2.0 * density);
    zoom = (int) Math.floor(Math.log(tilesAcrossWorld) / Math.log(2));
    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    offX = pad + (float) (w - spanX * scale) / 2;
    offY = pad + (float) (h - spanY * scale) / 2;

    for (int i = 0; i < route.length; i += 2) {
      float x = toViewX(worldX(route[i + 1]));
      float y = toViewY(worldY(route[i]));
      if (i == 0) {
        path.moveTo(x, y);
        startX = x;
        startY = y;
      } else {
        path.lineTo(x, y);
      }
      endX = x;
      endY = y;
    }
  }

  private void drawTiles(Canvas canvas) {
    MapTileLoader loader = MapTileLoader.get(getContext());
    int tiles = 1 << zoom;
    double tileWorld = 1.0 / tiles;
    // world coordinates of the view corners
    double left = minWorldX - offX / scale;
    double top = minWorldY - offY / scale;
    double right = left + getWidth() / scale;
    double bottom = top + getHeight() / scale;

    for (int ty = (int) Math.floor(top * tiles); ty <= (int) Math.floor(bottom * tiles); ty++) {
      for (int tx = (int) Math.floor(left * tiles); tx <= (int) Math.floor(right * tiles); tx++) {
        if (ty < 0 || ty >= tiles) {
          continue;
        }
        Bitmap tile = loader.getTile(zoom, Math.floorMod(tx, tiles), ty, onTileLoaded);
        if (tile == null) {
          continue;
        }
        tileRect.set(
            toViewX(tx * tileWorld),
            toViewY(ty * tileWorld),
            toViewX((tx + 1) * tileWorld),
            toViewY((ty + 1) * tileWorld));
        canvas.drawBitmap(tile, null, tileRect, tilePaint);
      }
    }
  }

  private void drawGrid(Canvas canvas) {
    float step = 28 * density;
    for (float x = step; x < getWidth(); x += step) {
      canvas.drawLine(x, 0, x, getHeight(), gridPaint);
    }
    for (float y = step; y < getHeight(); y += step) {
      canvas.drawLine(0, y, getWidth(), y, gridPaint);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(backgroundColor);

    if (!pathValid) {
      buildPath();
    }
    if (path.isEmpty()) {
      drawGrid(canvas);
      return;
    }

    drawGrid(canvas); // also the placeholder under tiles that are still loading
    if (MAP_TILES_ENABLED) {
      drawTiles(canvas);
    }

    canvas.drawPath(path, shadowPaint);
    canvas.drawPath(path, routePaint);

    // start: route-colored dot with white ring, end: white dot with dark ring
    dotPaint.setColor(Color.WHITE);
    canvas.drawCircle(startX, startY, 6 * density, dotPaint);
    dotPaint.setColor(routePaint.getColor());
    canvas.drawCircle(startX, startY, 4 * density, dotPaint);
    dotPaint.setColor(Color.BLACK);
    canvas.drawCircle(endX, endY, 6 * density, dotPaint);
    dotPaint.setColor(Color.WHITE);
    canvas.drawCircle(endX, endY, 4 * density, dotPaint);

    if (MAP_TILES_ENABLED) {
      float textWidth = attributionPaint.measureText(attribution);
      float textPad = 4 * density;
      float baseline = getHeight() - textPad - attributionPaint.descent();
      canvas.drawRect(
          getWidth() - textWidth - 2 * textPad,
          baseline + attributionPaint.ascent() - textPad / 2,
          getWidth(),
          getHeight(),
          attributionBgPaint);
      canvas.drawText(attribution, getWidth() - textWidth - textPad, baseline, attributionPaint);
    }
  }
}
