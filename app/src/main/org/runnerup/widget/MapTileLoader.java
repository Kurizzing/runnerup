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

/**
 * Loads OpenStreetMap raster tiles for the small route previews, with a memory cache and an HTTP
 * disk cache so scrolling the history list does not refetch tiles. All public calls are made on the
 * main thread.
 */
final class MapTileLoader {

  private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
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

  static MapTileLoader get(Context context) {
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
  Bitmap getTile(int zoom, int x, int y, Runnable onLoaded) {
    String key = zoom + "/" + x + "/" + y;
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

    String url = String.format(Locale.ROOT, TILE_URL, zoom, x, y);
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
