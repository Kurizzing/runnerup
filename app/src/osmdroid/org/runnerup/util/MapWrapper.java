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

package org.runnerup.util;

import static org.runnerup.util.Formatter.Format.TXT_SHORT;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.runnerup.R;
import org.runnerup.common.util.Constants;
import org.runnerup.db.entities.LocationEntity;
import org.runnerup.widget.MapTileLoader;

public class MapWrapper implements Constants {

  private final SQLiteDatabase mDB;
  private final long mID;
  private final Formatter formatter;
  private final MapView mapView;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private static final java.lang.String OSMDROID_USER_AGENT = "org.runnerup.free";

  public MapWrapper(
      Context context,
      SQLiteDatabase mDB,
      long mID,
      Formatter formatter,
      java.lang.Object mapView) {
    this.mDB = mDB;
    this.mID = mID;
    this.formatter = formatter;
    this.mapView = (MapView) mapView;
  }

  public static void start(Context context) {}

  public void onCreate(Bundle savedInstanceState) {
    if (MapTileLoader.useSimpleMap(mapView.getContext())) {
      // Simple map style (settings), same HOT map as in the route previews
      mapView.setTileSource(
          new XYTileSource(
              "HOT",
              0,
              20,
              256,
              ".png",
              new String[] {
                "https://a.tile.openstreetmap.fr/hot/",
                "https://b.tile.openstreetmap.fr/hot/",
                "https://c.tile.openstreetmap.fr/hot/"
              },
              mapView.getContext().getString(R.string.Map_attribution_hot)));
    } else {
      mapView.setTileSource(TileSourceFactory.MAPNIK);
    }
    mapView
        .getZoomController()
        // Own buttons instead (map_controls), styled like the app
        .setVisibility(CustomZoomButtonsController.Visibility.NEVER);
    mapView.setMultiTouchControls(true);
    setupControls();

    Configuration.getInstance().setUserAgentValue(OSMDROID_USER_AGENT);

    loadRouteAsync();
  }

  // The results from the database query
  record Route(
      Polyline casing,
      Polyline line,
      LapLabelsOverlay laps,
      List<Marker> markers,
      List<GeoPoint> points,
      @Nullable List<LapInfo> lapList) {}

  /** A lap start: number, number color (null for the default style) and distance and time */
  record LapInfo(GeoPoint point, String number, @Nullable Integer color, String details) {}

  /** Closest zoom when fitting a short route, so the map keeps some context */
  private static final double MAX_FIT_ZOOM = 18;

  /** Space between the route and the map edges, in dp */
  private static final int FIT_BORDER_DP = 48;

  /** Start and finish closer than this (m) are shown as one marker */
  private static final double SAME_PLACE_METERS = 50;

  /** Setting: fit the route beside the lap list instead of under it */
  private boolean keepClearOfList = true;

  /** Points of the route, for the "whole route" button */
  private List<GeoPoint> routePoints = new ArrayList<>();

  /** Zoom in/out and whole route buttons on top of the map (in the detail layout) */
  private void setupControls() {
    View parent = (View) mapView.getParent();
    View controls = parent.findViewById(R.id.map_controls);
    if (controls == null) {
      return;
    }
    controls.setVisibility(View.VISIBLE);
    parent.findViewById(R.id.map_zoom_in).setOnClickListener(v -> mapView.getController().zoomIn());
    parent
        .findViewById(R.id.map_zoom_out)
        .setOnClickListener(v -> mapView.getController().zoomOut());
    parent.findViewById(R.id.map_fit).setOnClickListener(v -> zoomToRoute(routePoints, true));
  }

  /**
   * Loads the route from the database on a background thread and then updates the UI on the main
   * thread.
   */
  private void loadRouteAsync() {
    executor.execute(
        () -> {
          final Route route = loadRouteData();

          // UI update
          mapView.post(
              () -> {
                // The casing under the line, then the markers on top
                mapView.getOverlays().add(route.casing);
                mapView.getOverlays().add(route.line);
                mapView.getOverlays().add(route.laps);
                mapView.getOverlays().addAll(route.markers);
                routePoints = route.points;
                if (route.lapList != null) {
                  showLapList(route.lapList, route.laps);
                }
                zoomToRoute(route.points, false);
                mapView.invalidate();
              });
        });
  }

  /**
   * @return width (px) of the open lap list on the right of the map, 0 if not shown
   */
  private int lapPanelWidth() {
    View parent = (View) mapView.getParent();
    View panel = parent.findViewById(R.id.map_lap_panel);
    View scroll = parent.findViewById(R.id.map_lap_scroll);
    if (panel == null
        || panel.getVisibility() != View.VISIBLE
        || scroll.getVisibility() != View.VISIBLE) {
      return 0;
    }
    if (panel.getWidth() == 0) {
      // Not laid out yet
      panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
      return panel.getMeasuredWidth();
    }
    return panel.getWidth();
  }

  /** Shows the whole route as large as possible (the map tab may not be laid out yet) */
  private void zoomToRoute(List<GeoPoint> points, boolean animated) {
    if (points.isEmpty()) {
      return;
    }
    Runnable fit =
        () -> {
          if (points.size() < 2) {
            mapView.getController().setZoom(MAX_FIT_ZOOM - 2);
            mapView.getController().setCenter(points.get(0));
            return;
          }
          BoundingBox box = BoundingBox.fromGeoPointsSafe(points);
          int border =
              Math.round(FIT_BORDER_DP * mapView.getResources().getDisplayMetrics().density);
          int panel = keepClearOfList ? lapPanelWidth() : 0;
          int free = mapView.getWidth() - panel - 2 * border;
          if (panel > 0 && free > 0) {
            // Keep the route left of the lap list: widen the box east by the panel width
            double lonPerPixel = box.getLongitudeSpan() / free;
            box =
                new BoundingBox(
                    box.getLatNorth(),
                    box.getLonEast() + lonPerPixel * panel,
                    box.getLatSouth(),
                    box.getLonWest());
          }
          mapView.zoomToBoundingBox(box, animated, border, MAX_FIT_ZOOM, null);
        };
    if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
      fit.run();
    } else {
      mapView.getController().setCenter(points.get(0));
      mapView.addOnFirstLayoutListener((v, left, top, right, bottom) -> fit.run());
    }
  }

  /** The long-running database query and data processing logic. */
  private Route loadRouteData() {
    Context context = mapView.getContext();
    MapMarkers icons = new MapMarkers(context);

    // White casing under the route keeps it readable on every map style
    Polyline casing = new Polyline(mapView, true);
    casing.setInfoWindow(null);
    stroke(casing, 9, ContextCompat.getColor(context, R.color.mapRouteCasing));
    Polyline line = new Polyline(mapView, true);
    line.setInfoWindow(null);
    stroke(line, 5, ContextCompat.getColor(context, R.color.colorAccent));

    java.util.List<Marker> markers = new ArrayList<>();
    java.util.List<LapInfo> lapInfos = new ArrayList<>();
    java.util.List<GeoPoint> points = new ArrayList<>();

    // Settings: lap values, colors and where the values are shown
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
    boolean lapPace =
        "pace".equals(prefs.getString(context.getString(R.string.pref_map_lap_values), "time"));
    keepClearOfList = prefs.getBoolean(context.getString(R.string.pref_map_lap_list_fit), true);

    LocationEntity.LocationList<LocationEntity> ll = new LocationEntity.LocationList<>(mDB, mID);
    int lastLap = -1;
    double lapStartDistance = 0;
    double lapStartTime = 0;
    for (LocationEntity loc : ll) {
      GeoPoint point = new GeoPoint(loc.getLatitude(), loc.getLongitude());
      points.add(point);

      int lap = loc.getLap();
      if (lastLap != lap) {
        if (lastLap != -1) {
          // Start of a new lap, so the end of the previous one: numbered badge with the
          // distance so far and the total time or the pace of the lap that ended here
          double distance = loc.getDistance();
          double time = loc.getElapsed() / 1000.0;
          String value;
          if (lapPace) {
            double meters = distance - lapStartDistance;
            value =
                meters > 0
                    ? formatter.formatPace(
                        Formatter.Format.TXT_LONG, (time - lapStartTime) / meters)
                    : "-";
          } else {
            value = formatter.formatElapsedTime(TXT_SHORT, Math.round(time));
          }
          String details =
              formatter.formatDistance(TXT_SHORT, Math.round(distance)) + "  ·  " + value;
          lapInfos.add(new LapInfo(point, Integer.toString(lap), null, details));
          lapStartDistance = distance;
          lapStartTime = time;
        }
        lastLap = lap;
      }
    }
    ll.close();
    casing.setPoints(points);

    if (prefs.getBoolean(context.getString(R.string.pref_map_lap_gradient), false)) {
      int start =
          parseColor(prefs, context.getString(R.string.pref_map_lap_color_start), 0xFF22C55E);
      int end = parseColor(prefs, context.getString(R.string.pref_map_lap_color_end), 0xFFEF4444);
      int n = lapInfos.size();
      for (int i = 0; i < n; i++) {
        LapInfo info = lapInfos.get(i);
        float t = n > 1 ? i / (float) (n - 1) : 0;
        lapInfos.set(
            i,
            new LapInfo(info.point, info.number, MapMarkers.gradient(start, end, t), info.details));
      }
    }
    boolean asList =
        "list".equals(prefs.getString(context.getString(R.string.pref_map_lap_labels), "next"));
    java.util.List<LapLabelsOverlay.Lap> laps = new ArrayList<>();
    for (LapInfo info : lapInfos) {
      laps.add(
          new LapLabelsOverlay.Lap(
              info.point,
              icons.lap(info.number, info.details, false, info.color),
              icons.lap(info.number, info.details, true, info.color),
              icons.lap(info.number, null, false, info.color)));
    }
    line.setPoints(points);

    if (!points.isEmpty()) {
      GeoPoint first = points.get(0);
      GeoPoint last = points.get(points.size() - 1);
      if (points.size() > 1 && first.distanceToAsDouble(last) >= SAME_PLACE_METERS) {
        markers.add(0, endMarker(icons.start(), first));
        markers.add(endMarker(icons.finish(), last));
      } else {
        // Loop or out-and-back: one start/finish marker
        markers.add(endMarker(icons.startFinish(), last));
      }
    }
    LapLabelsOverlay labels =
        new LapLabelsOverlay(
            laps,
            icons.badgeCenter(),
            icons.badgeMargin(),
            ContextCompat.getColor(context, R.color.dialogBackground),
            context.getResources().getDisplayMetrics().density);
    for (Marker marker : markers) {
      labels.avoid(marker.getPosition());
    }
    // With the list, only the numbers: tap one to show its values next to it
    labels.setValuesOnTap(asList);
    return new Route(casing, line, labels, markers, points, asList ? lapInfos : null);
  }

  private static int parseColor(SharedPreferences prefs, String key, int defaultColor) {
    try {
      return Color.parseColor(prefs.getString(key, ""));
    } catch (IllegalArgumentException e) {
      return defaultColor;
    }
  }

  /** Laps listed in a panel at the top right of the map; tap a lap to center the map on it */
  private void showLapList(List<LapInfo> lapList, LapLabelsOverlay labels) {
    View parent = (View) mapView.getParent();
    View panel = parent.findViewById(R.id.map_lap_panel);
    if (panel == null || lapList.isEmpty()) {
      return;
    }
    Context context = mapView.getContext();
    float density = context.getResources().getDisplayMetrics().density;
    LinearLayout rows = parent.findViewById(R.id.map_lap_rows);
    ScrollView scroll = parent.findViewById(R.id.map_lap_scroll);
    TextView header = parent.findViewById(R.id.map_lap_header);
    int accent = ContextCompat.getColor(context, R.color.colorAccent);
    MapMarkers icons = new MapMarkers(context);
    TypedValue ripple = new TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

    rows.removeAllViews();
    for (int i = 0; i < lapList.size(); i++) {
      LapInfo info = lapList.get(i);
      int index = i;
      LinearLayout row = new LinearLayout(context);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      int padding = Math.round(5 * density);
      row.setPadding(2 * padding, padding, Math.round(12 * density), padding);
      row.setBackgroundResource(ripple.resourceId);

      int color = info.color != null ? info.color : accent;
      TextView number = new TextView(context);
      int size = Math.round(22 * density);
      number.setLayoutParams(new LinearLayout.LayoutParams(size, size));
      number.setGravity(Gravity.CENTER);
      number.setText(info.number);
      number.setTextSize(11);
      number.setTypeface(Typeface.DEFAULT_BOLD);
      number.setTextColor(icons.textOn(color));
      GradientDrawable circle = new GradientDrawable();
      circle.setShape(GradientDrawable.OVAL);
      circle.setColor(color);
      number.setBackground(circle);

      TextView details = new TextView(context);
      details.setText(info.details);
      details.setTextSize(13);
      details.setTypeface(Typeface.DEFAULT_BOLD);
      details.setTextColor(ContextCompat.getColor(context, R.color.colorText));
      details.setPadding(2 * padding, 0, 0, 0);

      row.addView(number);
      row.addView(details);
      row.setOnClickListener(
          v -> {
            // Show the lap values on the map too
            labels.open(index);
            mapView.getController().animateTo(info.point);
            mapView.invalidate();
          });
      rows.addView(row);
    }
    header.setOnClickListener(
        v -> {
          boolean open = scroll.getVisibility() == View.VISIBLE;
          scroll.setVisibility(open ? View.GONE : View.VISIBLE);
          header.setText(
              open
                  ? org.runnerup.common.R.string.Map_laps_show
                  : org.runnerup.common.R.string.Map_laps_hide);
        });
    panel.setVisibility(View.VISIBLE);
    // At most about half of the map, the rest scrolls
    scroll.post(
        () -> {
          int max = mapView.getHeight() / 2;
          if (max > 0 && scroll.getHeight() > max) {
            scroll.getLayoutParams().height = max;
            scroll.requestLayout();
          }
        });
  }

  private void stroke(Polyline polyline, float widthDp, int color) {
    Paint paint = polyline.getOutlinePaint();
    paint.setStrokeWidth(widthDp * mapView.getResources().getDisplayMetrics().density);
    paint.setColor(color);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setAntiAlias(true);
  }

  private Marker endMarker(Drawable icon, GeoPoint point) {
    Marker marker = new Marker(mapView);
    marker.setPosition(point);
    marker.setIcon(icon);
    marker.setInfoWindow(null);
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
    marker.setOnMarkerClickListener((m, v) -> true);
    return marker;
  }

  public void onResume() {
    mapView.onResume();
  }

  public void onStart() {}

  public void onStop() {}

  public void onPause() {
    mapView.onPause();
  }

  public void onSaveInstanceState(Bundle outState) {}

  public void onLowMemory() {}

  public void onDestroy() {
    executor.shutdown();
  }
}
