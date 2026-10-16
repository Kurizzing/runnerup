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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import java.util.ArrayList;
import java.util.List;
import org.runnerup.BuildConfig;
import org.runnerup.R;
import org.runnerup.common.util.Constants.DB;
import org.runnerup.db.entities.ActivityEntity;
import org.runnerup.util.Formatter;
import org.runnerup.widget.MapTileLoader;
import org.runnerup.workout.Sport;

/**
 * Renders a portrait image of an activity for sharing on social media: the route on a map with the
 * title, distance, pace and time. Without a GPS track the sport icon is shown instead.
 */
final class ActivityShareImage {

  private static final int WIDTH = 1080;
  private static final int HEIGHT = 1920;
  private static final int MARGIN = 100;
  private static final int TILE_SIZE = 256;
  private static final int MAX_ZOOM = 18;
  private static final int MAX_ROUTE_POINTS = 1500;
  private static final int ROUTE_COLOR = 0xFFFF5722;
  private static final int MAP_FALLBACK_COLOR = 0xFFE8E4DC;
  // Not app_name: debug builds change that
  private static final String BRAND = "RunnerUp";

  // Area the route is fitted into, above the statistics
  private static final RectF ROUTE_AREA = new RectF(MARGIN, 200, WIDTH - MARGIN, 1180);

  private ActivityShareImage() {}

  /** Renders the image. Does database and network access, so call it on a background thread. */
  static Bitmap render(Context context, SQLiteDatabase db, long activityId, Formatter formatter) {
    ActivityEntity activity = loadActivity(db, activityId);
    Integer s = activity.getSport();
    int sport = s != null ? s : DB.ACTIVITY.SPORT_OTHER;
    List<double[]> route =
        Sport.isWithoutGps(sport) ? new ArrayList<>() : loadRoute(db, activityId);

    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    boolean mapDrawn = false;
    if (route.size() >= 2) {
      mapDrawn = drawMapAndRoute(context, canvas, route);
    } else {
      drawSportBackground(context, canvas, sport);
    }

    // Darken the lower part so the white text is readable on any map
    Paint shade = new Paint();
    shade.setShader(
        new LinearGradient(
            0, HEIGHT * 0.45f, 0, HEIGHT, 0x00000000, 0xE6000000, Shader.TileMode.CLAMP));
    canvas.drawRect(0, HEIGHT * 0.45f, WIDTH, HEIGHT, shade);

    drawStatistics(context, canvas, activity, sport, formatter);
    if (mapDrawn) {
      TextPaint small = textPaint(28, false);
      small.setAlpha(200);
      small.setTextAlign(Paint.Align.RIGHT);
      canvas.drawText(
          context.getString(mapStyle(context).attribution), WIDTH - 40, HEIGHT - 36, small);
    }
    return bitmap;
  }

  /** Map for sharing: detailed OpenStreetMap or the simple HOT map */
  private static MapTileLoader.Style mapStyle(Context context) {
    return MapTileLoader.useSimpleMap(context) ? MapTileLoader.Style.HOT : MapTileLoader.Style.OSM;
  }

  private static ActivityEntity loadActivity(SQLiteDatabase db, long activityId) {
    String[] columns = {
      DB.ACTIVITY.START_TIME,
      DB.ACTIVITY.DISTANCE,
      DB.ACTIVITY.TIME,
      DB.ACTIVITY.NAME,
      DB.ACTIVITY.SPORT
    };
    try (Cursor c =
        db.query(
            DB.ACTIVITY.TABLE,
            columns,
            "_id = ?",
            new String[] {Long.toString(activityId)},
            null,
            null,
            null)) {
      c.moveToFirst();
      return new ActivityEntity(c);
    }
  }

  /** GPS track as {latitude, longitude} pairs, thinned to about MAX_ROUTE_POINTS */
  private static List<double[]> loadRoute(SQLiteDatabase db, long activityId) {
    List<double[]> route = new ArrayList<>();
    String[] columns = {DB.LOCATION.LATITUDE, DB.LOCATION.LONGITUDE};
    try (Cursor c =
        db.query(
            DB.LOCATION.TABLE,
            columns,
            DB.LOCATION.ACTIVITY + " = ? and " + DB.LOCATION.TYPE + " = ?",
            new String[] {Long.toString(activityId), Integer.toString(DB.LOCATION.TYPE_GPS)},
            null,
            null,
            "_id")) {
      int stride = Math.max(1, c.getCount() / MAX_ROUTE_POINTS);
      while (c.moveToNext()) {
        if (c.getPosition() % stride == 0 || c.isLast()) {
          route.add(new double[] {c.getDouble(0), c.getDouble(1)});
        }
      }
    }
    return route;
  }

  private static double worldX(double lon) {
    return (lon + 180) / 360;
  }

  private static double worldY(double lat) {
    double sin = Math.sin(Math.toRadians(Math.max(-85, Math.min(85, lat))));
    return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  }

  /**
   * @return true if map tiles were drawn
   */
  private static boolean drawMapAndRoute(Context context, Canvas canvas, List<double[]> route) {
    double minX = 2, minY = 2, maxX = -1, maxY = -1;
    for (double[] p : route) {
      minX = Math.min(minX, worldX(p[1]));
      maxX = Math.max(maxX, worldX(p[1]));
      minY = Math.min(minY, worldY(p[0]));
      maxY = Math.max(maxY, worldY(p[0]));
    }
    double spanX = Math.max(maxX - minX, 1e-9);
    double spanY = Math.max(maxY - minY, 1e-9);
    // Pixels per world unit, limited so that very short routes are not zoomed in endlessly
    double scale =
        Math.min(
            Math.min(ROUTE_AREA.width() / spanX, ROUTE_AREA.height() / spanY),
            TILE_SIZE * 1.5 * (1 << MAX_ZOOM));
    double originX = minX - (ROUTE_AREA.left + (ROUTE_AREA.width() - spanX * scale) / 2) / scale;
    double originY = minY - (ROUTE_AREA.top + (ROUTE_AREA.height() - spanY * scale) / 2) / scale;

    canvas.drawColor(MAP_FALLBACK_COLOR);
    boolean mapDrawn = false;
    if (BuildConfig.OSMDROID_ENABLED || BuildConfig.MAPBOX_ENABLED) {
      // Tiles drawn at 1.5x - 3x of their size, so that map labels stay readable
      int zoom = (int) Math.floor(Math.log(scale / (TILE_SIZE * 1.5)) / Math.log(2));
      zoom = Math.max(1, Math.min(MAX_ZOOM, zoom));
      int tiles = 1 << zoom;
      MapTileLoader loader = MapTileLoader.get(context);
      MapTileLoader.Style style = mapStyle(context);
      Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
      RectF rect = new RectF();
      int firstX = (int) Math.floor(originX * tiles);
      int lastX = (int) Math.floor((originX + WIDTH / scale) * tiles);
      int firstY = Math.max(0, (int) Math.floor(originY * tiles));
      int lastY = Math.min(tiles - 1, (int) Math.floor((originY + HEIGHT / scale) * tiles));
      for (int ty = firstY; ty <= lastY; ty++) {
        for (int tx = firstX; tx <= lastX; tx++) {
          Bitmap tile = loader.getTileBlocking(style, zoom, Math.floorMod(tx, tiles), ty);
          if (tile == null) {
            continue;
          }
          rect.set(
              (float) (((double) tx / tiles - originX) * scale),
              (float) (((double) ty / tiles - originY) * scale),
              (float) (((double) (tx + 1) / tiles - originX) * scale),
              (float) (((double) (ty + 1) / tiles - originY) * scale));
          canvas.drawBitmap(tile, null, rect, tilePaint);
          mapDrawn = true;
        }
      }
    }

    if (mapDrawn) {
      // Soften the map a little so the route stands out
      canvas.drawColor(0x26FFFFFF);
    }

    Path path = new Path();
    for (int i = 0; i < route.size(); i++) {
      float x = (float) ((worldX(route.get(i)[1]) - originX) * scale);
      float y = (float) ((worldY(route.get(i)[0]) - originY) * scale);
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    Paint outline = routePaint(Color.WHITE, 16);
    outline.setAlpha(170);
    canvas.drawPath(path, outline);
    canvas.drawPath(path, routePaint(ROUTE_COLOR, 9));
    return mapDrawn;
  }

  private static Paint routePaint(int color, float width) {
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(width);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setColor(color);
    return paint;
  }

  private static void drawSportBackground(Context context, Canvas canvas, int sport) {
    Paint bg = new Paint();
    bg.setShader(
        new LinearGradient(
            0,
            0,
            0,
            HEIGHT,
            ContextCompat.getColor(context, R.color.cardBackground),
            ContextCompat.getColor(context, R.color.appBackground),
            Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, WIDTH, HEIGHT, bg);
    Drawable icon = AppCompatResources.getDrawable(context, Sport.drawableColored16Of(sport));
    if (icon != null) {
      int size = 420;
      int left = (WIDTH - size) / 2;
      icon.setBounds(left, 380, left + size, 380 + size);
      icon.draw(canvas);
    }
  }

  private static void drawStatistics(
      Context context, Canvas canvas, ActivityEntity activity, int sport, Formatter formatter) {
    Double d = activity.getDistance();
    Long t = activity.getTime();
    double distance = d != null ? d : 0;
    long time = t != null ? t : 0;
    String sportName = Sport.textOf(context.getResources(), sport);
    String name = activity.getName();
    String title =
        TextUtils.isEmpty(name)
            ? HistoryFragment.defaultTitle(context, activity.getStartTime(), sportName)
            : name;

    TextPaint label = textPaint(44, false);
    label.setAlpha(220);
    TextPaint value = textPaint(96, true);
    TextPaint big = textPaint(128, true);

    // Sport icon (white) and app name
    Drawable icon = AppCompatResources.getDrawable(context, Sport.drawableColored16Of(sport));
    if (icon != null) {
      icon = DrawableCompat.wrap(icon.mutate());
      DrawableCompat.setTint(icon, Color.WHITE);
      icon.setBounds(MARGIN, 1110, MARGIN + 110, 1220);
      icon.draw(canvas);
    }
    TextPaint brand = textPaint(60, true);
    brand.setTextAlign(Paint.Align.RIGHT);
    canvas.drawText(BRAND, WIDTH - MARGIN, 1195, brand);

    TextPaint titlePaint = textPaint(68, true);
    canvas.drawText(
        TextUtils.ellipsize(title, titlePaint, WIDTH - 2 * MARGIN, TextUtils.TruncateAt.END)
            .toString(),
        MARGIN,
        1320,
        titlePaint);

    String timeText = formatter.formatElapsedTime(Formatter.Format.TXT_SHORT, time);
    if (distance > 0) {
      String pace =
          time > 0
              ? formatter.formatVelocityByPreferredUnit(Formatter.Format.TXT_LONG, distance / time)
              : "";
      // Time column after the pace value, which can be wide (e.g. "08:29 /mi")
      float timeX = Math.max(580, MARGIN + value.measureText(pace) + 70);
      canvas.drawText(formatter.formatVelocityLabel(), MARGIN, 1440, label);
      canvas.drawText(pace, MARGIN, 1550, value);
      canvas.drawText(context.getString(org.runnerup.common.R.string.Time), timeX, 1440, label);
      canvas.drawText(timeText, timeX, 1550, value);
      canvas.drawText(
          context.getString(org.runnerup.common.R.string.Distance), MARGIN, 1670, label);
      canvas.drawText(
          formatter.formatDistance(Formatter.Format.TXT_SHORT, (long) distance), MARGIN, 1800, big);
    } else {
      canvas.drawText(context.getString(org.runnerup.common.R.string.Time), MARGIN, 1670, label);
      canvas.drawText(timeText, MARGIN, 1800, big);
    }
  }

  private static TextPaint textPaint(float size, boolean bold) {
    TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.WHITE);
    paint.setTextSize(size);
    paint.setTypeface(
        bold
            ? Typeface.create("sans-serif", Typeface.BOLD)
            : Typeface.create("sans-serif-medium", Typeface.NORMAL));
    paint.setShadowLayer(6, 0, 2, 0x66000000);
    return paint;
  }
}
