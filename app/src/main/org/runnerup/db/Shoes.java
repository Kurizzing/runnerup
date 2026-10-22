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

package org.runnerup.db;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.List;
import org.runnerup.R;
import org.runnerup.common.util.Constants.DB;

/**
 * Shoes (gear): the shoe table, the shoe of each activity and the default shoe. The distance of a
 * shoe is the total distance of the (not deleted) activities it was used in.
 */
public final class Shoes {

  public static final long NONE = -1;

  public static final class Shoe {
    public long id = NONE;
    public String name = "";

    /** Seconds since epoch, 0 if not set */
    public long purchaseDate = 0;

    /** null if not set */
    public Double price = null;

    /** null or empty if not set */
    public String size = null;

    public String notes = null;
    public boolean retired = false;

    /** Total distance (m) of the activities with this shoe, read only */
    public double distance = 0;
  }

  private Shoes() {}

  /** Creates the shoe table and the shoe columns if missing */
  static void ensureSchema(SQLiteDatabase db) {
    db.execSQL(
        "create table if not exists "
            + DB.SHOE.TABLE
            + " ( _id integer primary key autoincrement, "
            + DB.SHOE.NAME
            + " text not null, "
            + DB.SHOE.RETIRED
            + " integer not null default 0 );");
    addColumnIfMissing(db, DB.SHOE.TABLE, DB.SHOE.PURCHASE_DATE, "integer");
    addColumnIfMissing(db, DB.SHOE.TABLE, DB.SHOE.PRICE, "real");
    addColumnIfMissing(db, DB.SHOE.TABLE, DB.SHOE.SIZE, "text");
    addColumnIfMissing(db, DB.SHOE.TABLE, DB.SHOE.NOTES, "text");
    addColumnIfMissing(db, DB.ACTIVITY.TABLE, DB.ACTIVITY.SHOE, "integer");
  }

  private static void addColumnIfMissing(
      SQLiteDatabase db, String table, String column, String type) {
    try (Cursor c = db.rawQuery("pragma table_info(" + table + ")", null)) {
      int nameIndex = c.getColumnIndex("name");
      while (c.moveToNext()) {
        if (column.equals(c.getString(nameIndex))) {
          return;
        }
      }
    }
    db.execSQL("alter table " + table + " add column " + column + " " + type);
  }

  /**
   * @param includeRetired false to list only the shoes in use
   */
  public static List<Shoe> list(SQLiteDatabase db, boolean includeRetired) {
    String sql =
        "select s._id, s."
            + DB.SHOE.NAME
            + ", s."
            + DB.SHOE.PURCHASE_DATE
            + ", s."
            + DB.SHOE.PRICE
            + ", s."
            + DB.SHOE.SIZE
            + ", s."
            + DB.SHOE.NOTES
            + ", s."
            + DB.SHOE.RETIRED
            + ", (select coalesce(sum(a."
            + DB.ACTIVITY.DISTANCE
            + "), 0) from "
            + DB.ACTIVITY.TABLE
            + " a where a."
            + DB.ACTIVITY.SHOE
            + " = s._id and a.deleted == 0)"
            + " from "
            + DB.SHOE.TABLE
            + " s"
            + (includeRetired ? "" : " where s." + DB.SHOE.RETIRED + " == 0")
            + " order by s."
            + DB.SHOE.RETIRED
            + ", s._id";
    List<Shoe> shoes = new ArrayList<>();
    try (Cursor c = db.rawQuery(sql, null)) {
      while (c.moveToNext()) {
        Shoe shoe = new Shoe();
        shoe.id = c.getLong(0);
        shoe.name = c.getString(1);
        shoe.purchaseDate = c.isNull(2) ? 0 : c.getLong(2);
        shoe.price = c.isNull(3) ? null : c.getDouble(3);
        shoe.size = c.getString(4);
        shoe.notes = c.getString(5);
        shoe.retired = c.getInt(6) != 0;
        shoe.distance = c.getDouble(7);
        shoes.add(shoe);
      }
    }
    return shoes;
  }

  /**
   * Inserts the shoe if its id is NONE, otherwise updates it
   *
   * @return the id of the shoe
   */
  public static long save(SQLiteDatabase db, Shoe shoe) {
    ContentValues values = new ContentValues();
    values.put(DB.SHOE.NAME, shoe.name);
    if (shoe.purchaseDate != 0) {
      values.put(DB.SHOE.PURCHASE_DATE, shoe.purchaseDate);
    } else {
      values.putNull(DB.SHOE.PURCHASE_DATE);
    }
    values.put(DB.SHOE.PRICE, shoe.price);
    values.put(DB.SHOE.SIZE, shoe.size);
    values.put(DB.SHOE.NOTES, shoe.notes);
    values.put(DB.SHOE.RETIRED, shoe.retired ? 1 : 0);
    if (shoe.id == NONE) {
      shoe.id = db.insert(DB.SHOE.TABLE, null, values);
    } else {
      db.update(DB.SHOE.TABLE, values, "_id = ?", new String[] {Long.toString(shoe.id)});
    }
    return shoe.id;
  }

  /** Deletes a shoe; its activities keep their data but no longer have a shoe */
  public static void delete(SQLiteDatabase db, long id) {
    ContentValues values = new ContentValues();
    values.putNull(DB.ACTIVITY.SHOE);
    String[] args = {Long.toString(id)};
    db.update(DB.ACTIVITY.TABLE, values, DB.ACTIVITY.SHOE + " = ?", args);
    db.delete(DB.SHOE.TABLE, "_id = ?", args);
  }

  private static SharedPreferences prefs(Context context) {
    return PreferenceManager.getDefaultSharedPreferences(context);
  }

  public static long getDefault(Context context) {
    return prefs(context).getLong(context.getString(R.string.pref_default_shoe), NONE);
  }

  /** Sets the default shoe, which is also selected on the start screen */
  public static void setDefault(Context context, long id) {
    prefs(context)
        .edit()
        .putLong(context.getString(R.string.pref_default_shoe), id)
        .putLong(context.getString(R.string.pref_current_shoe), id)
        .apply();
  }

  /** The shoe selected on the start screen, used for the next recorded activity */
  public static long getCurrent(Context context) {
    return prefs(context).getLong(context.getString(R.string.pref_current_shoe), NONE);
  }

  public static void setCurrent(Context context, long id) {
    prefs(context).edit().putLong(context.getString(R.string.pref_current_shoe), id).apply();
  }

  /**
   * @return the id if it is an existing shoe that is not retired, otherwise NONE
   */
  public static long validate(SQLiteDatabase db, long id) {
    if (id == NONE) {
      return NONE;
    }
    try (Cursor c =
        db.query(
            DB.SHOE.TABLE,
            new String[] {"_id"},
            "_id = ? and " + DB.SHOE.RETIRED + " == 0",
            new String[] {Long.toString(id)},
            null,
            null,
            null)) {
      return c.moveToFirst() ? id : NONE;
    }
  }
}
