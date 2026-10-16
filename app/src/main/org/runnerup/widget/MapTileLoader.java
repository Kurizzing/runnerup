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
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.runnerup.R;

/**
 * Loads map raster tiles (see {@link Style}) for route previews and share images, with a memory
 * cache and an HTTP disk cache so scrolling the history list does not refetch tiles. Call {@link
 * #getTile} on the main thread and {@link #getTileBlocking} on a background thread.
 */
public final class MapTileLoader {

  /** Tile sources: the detailed OpenStreetMap map and the simpler, lighter HOT map */
  public enum Style {
    OSM("https://tile.openstreetmap.org/%d/%d/%d.png", R.string.History_map_attribution),
    // Humanitarian OpenStreetMap style: pastel colors and fewer details (no API key needed)
    HOT("https://a.tile.openstreetmap.fr/hot/%d/%d/%d.png", R.string.Map_attribution_hot);

    final String url;
    public final int attribution;

    Style(String url, int attribution) {
      this.url = url;
      this.attribution = attribution;
    }
  }

  /**
   * @return true if the user chose the simple map style in the settings
   */
  public static boolean useSimpleMap(Context context) {
    return "simple"
        .equals(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.pref_map_style), "detailed"));
  }

  // Same identification as the osmdroid map in the activity details
  private static final String USER_AGENT = "org.runnerup.free";
  private static final long DISK_CACHE_BYTES = 30L * 1024 * 1024;
  private static final long RETRY_AFTER_MS = 60_000;

  private static MapTileLoader instance;

  private final OkHttpClient client;
  private final LruCache<String, Bitmap> memoryCache;
  private final Map<String, List<Runnable>> pending = new HashMap<>();

  /** Failed tile key to failure time, to avoid hammering the server while offline */
  private final Map<String, Long> failed = new HashMap<>();

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  public static MapTileLoader get(Context context) {
    if (instance == null) {
      instance = new MapTileLoader(context.getApplicationContext());
    }
    return instance;
  }

  private MapTileLoader(Context context) {
    client =
        new OkHttpClient.Builder()
            .cache(new Cache(new File(context.getCacheDir(), "route_tiles"), DISK_CACHE_BYTES))
            .build();
    int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 16);
    memoryCache =
        new LruCache<>(maxKb) {
          @Override
          protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
          }
        };
  }

  /**
   * @return the tile if cached, otherwise null and the tile is fetched; onLoaded is then run on the
   *     main thread when it arrives
   */
  @Nullable
  Bitmap getTile(Style style, int zoom, int x, int y, Runnable onLoaded) {
    String key = style + "/" + zoom + "/" + x + "/" + y;
    Bitmap bitmap = memoryCache.get(key);
    if (bitmap != null) {
      return bitmap;
    }
    Long failedAt = failed.get(key);
    if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) {
      return null;
    }
    List<Runnable> waiting = pending.get(key);
    if (waiting != null) {
      waiting.add(onLoaded);
      return null;
    }
    waiting = new ArrayList<>();
    waiting.add(onLoaded);
    pending.put(key, waiting);

    String url = String.format(Locale.ROOT, style.url, zoom, x, y);
    executor.execute(
        () -> {
          Bitmap result = download(url);
          mainHandler.post(
              () -> {
                if (result != null) {
                  memoryCache.put(key, result);
                  failed.remove(key);
                } else {
                  failed.put(key, System.currentTimeMillis());
                }
                List<Runnable> callbacks = pending.remove(key);
                if (result != null && callbacks != null) {
                  for (Runnable r : callbacks) {
                    r.run();
                  }
                }
              });
        });
    return null;
  }

  /**
   * Fetches a tile on the calling (background) thread, using the memory and disk caches.
   *
   * @return the tile, or null if it could not be loaded
   */
  @Nullable
  public Bitmap getTileBlocking(Style style, int zoom, int x, int y) {
    String key = style + "/" + zoom + "/" + x + "/" + y;
    Bitmap bitmap = memoryCache.get(key);
    if (bitmap == null) {
      bitmap = download(String.format(Locale.ROOT, style.url, zoom, x, y));
      if (bitmap != null) {
        memoryCache.put(key, bitmap);
      }
    }
    return bitmap;
  }

  @Nullable
  private Bitmap download(String url) {
    Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
    try (Response response = client.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (!response.isSuccessful()) {
        return null;
      }
      byte[] bytes = body.bytes();
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    } catch (Exception e) {
      return null;
    }
  }
}
