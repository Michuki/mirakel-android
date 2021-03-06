/*******************************************************************************
 * Mirakel is an Android App for managing your ToDo-Lists
 *
 * Copyright (c) 2013-2014 Anatolij Zelenin, Georg Semmler.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package de.azapps.mirakel.model;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.google.common.base.Optional;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.transform.TransformerException;

import de.azapps.mirakel.DefinitionsHelper;
import de.azapps.mirakel.DefinitionsHelper.SYNC_STATE;
import de.azapps.mirakel.helper.CompatibilityHelper;
import de.azapps.mirakel.helper.DateTimeHelper;
import de.azapps.mirakel.helper.Helpers;
import de.azapps.mirakel.helper.MirakelCommonPreferences;
import de.azapps.mirakel.helper.MirakelModelPreferences;
import de.azapps.mirakel.helper.MirakelPreferences;
import de.azapps.mirakel.helper.export_import.ExportImport;
import de.azapps.mirakel.model.account.AccountMirakel;
import de.azapps.mirakel.model.account.AccountMirakel.ACCOUNT_TYPES;
import de.azapps.mirakel.model.file.FileMirakel;
import de.azapps.mirakel.model.list.ListMirakel;
import de.azapps.mirakel.model.list.SpecialList;
import de.azapps.mirakel.model.list.meta.SpecialListsBaseProperty;
import de.azapps.mirakel.model.list.meta.SpecialListsContentProperty;
import de.azapps.mirakel.model.list.meta.SpecialListsListProperty;
import de.azapps.mirakel.model.list.meta.SpecialListsNameProperty;
import de.azapps.mirakel.model.list.meta.SpecialListsPriorityProperty;
import de.azapps.mirakel.model.recurring.Recurring;
import de.azapps.mirakel.model.semantic.Semantic;
import de.azapps.mirakel.model.tags.Tag;
import de.azapps.mirakel.model.task.Task;
import de.azapps.tools.FileUtils;
import de.azapps.tools.Log;

import static com.google.common.base.Optional.fromNullable;

public class DatabaseHelper extends SQLiteOpenHelper {

    public static final String CREATED_AT = "created_at";
    public static final int DATABASE_VERSION = 47;

    private static final String TAG = "DatabaseHelper";
    public static final String UPDATED_AT = "updated_at";
    public static final String SYNC_STATE_FIELD = "sync_state";

    private static void createAccountTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + AccountMirakel.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ModelBase.NAME
                   + " TEXT NOT NULL, " + "content TEXT, "
                   + AccountMirakel.ENABLED + " INTEGER NOT NULL DEFAULT 0, "
                   + AccountMirakel.TYPE + " INTEGER NOT NULL DEFAULT "
                   + ACCOUNT_TYPES.LOCAL.toInt() + ")");
    }

    protected static void createTasksTableOLD(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Task.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + Task.LIST_ID
                   + " INTEGER REFERENCES " + ListMirakel.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE, " + ModelBase.NAME
                   + " TEXT NOT NULL, " + "content TEXT, " + Task.DONE
                   + " INTEGER NOT NULL DEFAULT 0, " + Task.PRIORITY
                   + " INTEGER NOT NULL DEFAULT 0, " + Task.DUE + " STRING, "
                   + CREATED_AT + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + UPDATED_AT + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD + ")");
    }

    private final Context context;

    protected static void createTasksTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Task.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + Task.LIST_ID
                   + " INTEGER REFERENCES " + ListMirakel.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE, " + ModelBase.NAME
                   + " TEXT NOT NULL, " + "content TEXT, " + Task.DONE
                   + " INTEGER NOT NULL DEFAULT 0, " + Task.PRIORITY
                   + " INTEGER NOT NULL DEFAULT 0, " + Task.DUE + " STRING, "
                   + CREATED_AT + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + UPDATED_AT + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD + ","
                   + Task.REMINDER + " INTEGER," + Task.UUID
                   + " TEXT NOT NULL DEFAULT ''," + Task.ADDITIONAL_ENTRIES
                   + " TEXT NOT NULL DEFAULT ''," + Task.RECURRING
                   + " INTEGER DEFAULT '-1'," + Task.RECURRING_REMINDER
                   + " INTEGER DEFAULT '-1'," + Task.PROGRESS
                   + " INTEGER NOT NULL default 0)");
    }

    private DatabaseHelper(final Context ctx) {
        super(ctx, getDBName(ctx), null, DATABASE_VERSION);
        this.context = ctx;
    }

    private static DatabaseHelper databaseHelperSingleton;

    public static DatabaseHelper getDatabaseHelper(final Context context) {
        if (databaseHelperSingleton == null) {
            databaseHelperSingleton = new DatabaseHelper(context);
        }
        return databaseHelperSingleton;
    }

    /**
     * Returns the database name depending if Mirakel is in demo mode or not.
     *
     * If Mirakel is in demo mode, it creates for the current language a fresh
     * new database if it does not exist.
     *
     * @return
     */
    public static String getDBName(final Context ctx) {
        MirakelPreferences.init(ctx);
        return MirakelModelPreferences.getDBName();
    }

    private void createSpecialListsTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + SpecialList.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ModelBase. NAME
                   + " TEXT NOT NULL, " + SpecialList.ACTIVE
                   + " INTEGER NOT NULL DEFAULT 0, " + SpecialList.WHERE_QUERY
                   + " STRING NOT NULL DEFAULT '', " + ListMirakel.SORT_BY_FIELD
                   + " INTEGER NOT NULL DEFAULT " + ListMirakel.SORT_BY.OPT.getShort() + ", "
                   + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD
                   + ", " + SpecialList.DEFAULT_LIST + " INTEGER, "
                   + SpecialList.DEFAULT_DUE + " INTEGER," + ListMirakel.COLOR
                   + " INTEGER, " + ListMirakel.LFT + " INTEGER ,"
                   + ListMirakel.RGT + " INTEGER)");
        db.execSQL("INSERT INTO " + SpecialList.TABLE + " (" + ModelBase.NAME + ','
                   + SpecialList.ACTIVE + ',' + SpecialList.WHERE_QUERY + ','
                   + ListMirakel.LFT + ", " + ListMirakel.RGT + ") VALUES (" + '\''
                   + this.context.getString(R.string.list_all) + "',1,'"
                   + Task.DONE + "=0',1,2)");
        db.execSQL("INSERT INTO " + SpecialList.TABLE + " (" + ModelBase.NAME + ','
                   + SpecialList.ACTIVE + ',' + SpecialList.WHERE_QUERY + ','
                   + ListMirakel.LFT + ", " + ListMirakel.RGT + ','
                   + SpecialList.DEFAULT_DUE + ") VALUES (" + '\''
                   + this.context.getString(R.string.list_today) + "',1,'"
                   + Task.DUE + " not null and " + Task.DONE + "=0 and date("
                   + Task.DUE + ")<=date(\"now\",\"localtime\")',3,4,0)");
        db.execSQL("INSERT INTO " + SpecialList.TABLE + " (" + ModelBase.NAME + ','
                   + SpecialList.ACTIVE + ',' + SpecialList.WHERE_QUERY + ','
                   + ListMirakel.LFT + ", " + ListMirakel.RGT + ','
                   + SpecialList.DEFAULT_DUE + ") VALUES (" + '\''
                   + this.context.getString(R.string.list_week) + "',1,'"
                   + Task.DUE + " not null and " + Task.DONE + "=0 and date("
                   + Task.DUE
                   + ")<=date(\"now\",\"+7 day\",\"localtime\")',5,6,7)");
        db.execSQL("INSERT INTO " + SpecialList.TABLE + " (" + ModelBase.NAME + ','
                   + SpecialList.ACTIVE + "," + SpecialList.WHERE_QUERY + ','
                   + ListMirakel.LFT + ", " + ListMirakel.RGT + ','
                   + SpecialList.DEFAULT_DUE + ") VALUES (" + '\''
                   + this.context.getString(R.string.list_overdue) + "',1,'"
                   + Task.DUE + " not null and " + Task.DONE + "=0 and date("
                   + Task.DUE
                   + ")<=date(\"now\",\"-1 day\",\"localtime\")',7,8,-1)");
    }

    private void createRecurringTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE "
                   + Recurring.TABLE
                   + " (" + ModelBase.ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                   + "years INTEGER DEFAULT 0,"
                   + "months INTEGER DEFAULT 0,"
                   + "days INTEGER DEFAULT 0,"
                   + "hours INTEGER DEFAULT 0,"
                   + "minutes INTEGER DEFAULT 0,"
                   + "for_due INTEGER DEFAULT 0,"
                   + "label STRING, start_date String,"
                   + " end_date String, "
                   + "temporary int NOT NULL default 0,"
                   + " isExact INTEGER DEFAULT 0, "
                   + "monday INTEGER DEFAULT 0,"
                   + "tuesday INTEGER DEFAULT 0, "
                   + "wednesday INTEGER DEFAULT 0,"
                   + "thursday INTEGER DEFAULT 0, "
                   + "friday INTEGER DEFAULT 0,"
                   + "saturday INTEGER DEFAULT 0,"
                   + "sunnday INTEGER DEFAULT 0, "
                   + "derived_from INTEGER DEFAULT NULL);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(days,label,for_due) VALUES (1,'"
                   + this.context.getString(R.string.daily) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(days,label,for_due) VALUES (2,'"
                   + this.context.getString(R.string.second_day) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(days,label,for_due) VALUES (7,'"
                   + this.context.getString(R.string.weekly) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(days,label,for_due) VALUES (14,'"
                   + this.context.getString(R.string.two_weekly) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(months,label,for_due) VALUES (1,'"
                   + this.context.getString(R.string.monthly) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(years,label,for_due) VALUES (1,'"
                   + this.context.getString(R.string.yearly) + "',1);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(hours,label,for_due) VALUES (1,'"
                   + this.context.getString(R.string.hourly) + "',0);");
        db.execSQL("INSERT INTO " + Recurring.TABLE
                   + "(minutes,label,for_due) VALUES (1,'"
                   + this.context.getString(R.string.minutly) + "',0);");
    }

    private void createSemanticTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Semantic.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                   + "condition TEXT NOT NULL, " + "due INTEGER, "
                   + "priority INTEGER, " + "list INTEGER," + "default_list" + ModelBase.ID
                   + " INTEGER, weekday INTEGER);");
        db.execSQL("INSERT INTO semantic_conditions (condition,due) VALUES "
                   + "(\""
                   + this.context.getString(R.string.today).toLowerCase(
                       Helpers.getLocal(this.context))
                   + "\",0);"
                   + "INSERT INTO semantic_conditions (condition,due) VALUES (\""
                   + this.context.getString(R.string.tomorrow).toLowerCase(
                       Helpers.getLocal(this.context)) + "\",1);");
        final String[] weekdays = this.context.getResources().getStringArray(
                                      R.array.weekdays);
        for (int i = 1; i < weekdays.length; i++) { // Ignore first element
            db.execSQL("INSERT INTO " + Semantic.TABLE + " ("
                       + Semantic.CONDITION + ',' + Semantic.WEEKDAY
                       + ") VALUES (?, " + i + ')', new String[] { weekdays[i] });
        }
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        DefinitionsHelper.freshInstall = true;
        createRecurringTable(db);
        createSemanticTable(db);
        createAccountTable(db);
        final String accountname = this.context
                                   .getString(R.string.local_account);
        ContentValues cv = new ContentValues();
        cv.put(ModelBase.NAME, accountname);
        final ACCOUNT_TYPES type = ACCOUNT_TYPES.LOCAL;
        cv.put(AccountMirakel.TYPE, type.toInt());
        cv.put(AccountMirakel.ENABLED, true);
        final long accountId = db.insert(AccountMirakel.TABLE, null, cv);
        createListsTable(db, accountId);
        createTasksTable(db);
        createSubtaskTable(db);
        createFileTable(db);
        createCalDavExtraTable(db);
        // Add defaults
        db.execSQL("INSERT INTO " + ListMirakel.TABLE + " (" + ModelBase.NAME + ','
                   + ListMirakel.LFT + ',' + ListMirakel.RGT + ") VALUES ('"
                   + this.context.getString(R.string.inbox) + "',0,1)");
        db.execSQL("INSERT INTO " + Task.TABLE + " (" + Task.LIST_ID + ','
                   + ModelBase.NAME + ") VALUES (1,'"
                   + this.context.getString(R.string.first_task) + "')");
        createSpecialListsTable(db);
        final String[] lists = this.context.getResources().getStringArray(
                                   R.array.demo_lists);
        for (int i = 0; i < lists.length; i++) {
            db.execSQL("INSERT INTO " + ListMirakel.TABLE + " (" + ModelBase.NAME + ','
                       + ListMirakel.LFT + ',' + ListMirakel.RGT + ") VALUES ('"
                       + lists[i] + "'," + (i + 2) + ',' + (i + 3) + ')');
        }
        MirakelInternalContentProvider.init(db);
        onUpgrade(db, 32, DATABASE_VERSION);
        if (MirakelCommonPreferences.isDemoMode()) {
            Semantic.init(context);
            final String[] tasks = this.context.getResources().getStringArray(
                                       R.array.demo_tasks);
            final String[] task_lists = { lists[1], lists[1], lists[1],
                                          lists[0], lists[2], lists[2]
                                        };
            final Calendar[] dues = new Calendar[6];
            dues[0] = new GregorianCalendar();
            dues[1] = null;
            dues[2] = new GregorianCalendar();
            while (dues[2].get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                dues[2].add(Calendar.DAY_OF_MONTH, 1);
            }
            dues[3] = new GregorianCalendar();
            dues[4] = null;
            dues[5] = null;
            final int[] priorities = { 2, -1, 1, 2, 0, 0 };
            for (int i = 0; i < tasks.length; i++) {
                final Task t = new Task(tasks[i], ListMirakel.findByName(task_lists[i]).get());
                t.setDue(fromNullable(dues[i]));
                t.setPriority(priorities[i]);
                t.setSyncState(SYNC_STATE.ADD);
                try {
                    cv = t.getContentValues();
                } catch (DefinitionsHelper.NoSuchListException e) {
                    Log.wtf(TAG, "missing list", e);
                }
                cv.remove(ModelBase.ID);
                db.insert(Task.TABLE, null, cv);
            }
        }
        MirakelInternalContentProvider.init(db);
    }

    private static void createListsTable(final SQLiteDatabase db,
                                         final long accountId) {
        db.execSQL("CREATE TABLE " + ListMirakel.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ModelBase.NAME
                   + " TEXT NOT NULL, " + ListMirakel.SORT_BY_FIELD
                   + " INTEGER NOT NULL DEFAULT 0, " + CREATED_AT
                   + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, " + UPDATED_AT
                   + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD
                   + ", " + ListMirakel.LFT + " INTEGER, " + ListMirakel.RGT
                   + " INTEGER " + ", " + ListMirakel.COLOR + " INTEGER,"
                   + ListMirakel.ACCOUNT_ID + " REFERENCES "
                   + AccountMirakel.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE DEFAULT " + accountId
                   + ')');
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldVersion,
                            final int newVersion) {
        Log.e(TAG, "You are downgrading the Database!");
        // This is only for developers… There shouldn't happen bad things if you
        // use a database with a higher version.
    }

    @SuppressWarnings("fallthrough")
    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldVersion,
                          final int newVersion) {
        Log.e(DatabaseHelper.class.getName(),
              "Upgrading database from version " + oldVersion + " to "
              + newVersion);
        try {
            ExportImport.exportDB(this.context);
        } catch (final RuntimeException e) {
            Log.w(TAG, "Cannot backup database", e);
        }
        switch (oldVersion) {
        case 1:// Nothing, Startversion
        case 2:
            // Add sync-state
            db.execSQL("Alter Table " + Task.TABLE + " add column "
                       + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD
                       + ';');
            db.execSQL("Alter Table " + ListMirakel.TABLE + " add column "
                       + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD
                       + ';');
            db.execSQL("CREATE TABLE settings (" + ModelBase.ID
                       + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                       + "server TEXT NOT NULL," + "user TEXT NOT NULL,"
                       + "password TEXT NOT NULL" + ')');
            db.execSQL("INSERT INTO settings (" + ModelBase.ID
                       + ",server,user,password)VALUES ('0','localhost','','')");
        case 3:
            // Add lft,rgt to lists
            // Set due to null, instate of 1970 in Tasks
            // Manage fromate of updated_at created_at in Tasks/Lists
            // drop settingssettings
            db.execSQL("UPDATE " + Task.TABLE + " set " + Task.DUE
                       + "='null' where " + Task.DUE + "='1970-01-01'");
            final String newDate = new SimpleDateFormat(
                this.context.getString(R.string.dateTimeFormat), Locale.US)
            .format(new Date());
            db.execSQL("UPDATE " + Task.TABLE + " set " + CREATED_AT + "='"
                       + newDate + '\'');
            db.execSQL("UPDATE " + Task.TABLE + " set " + UPDATED_AT + "='"
                       + newDate + '\'');
            db.execSQL("UPDATE " + ListMirakel.TABLE + " set " + CREATED_AT
                       + "='" + newDate + '\'');
            db.execSQL("UPDATE " + ListMirakel.TABLE + " set " + UPDATED_AT
                       + "='" + newDate + '\'');
            db.execSQL("Drop TABLE IF EXISTS settings");
        case 4:
            /*
             * Remove NOT NULL from Task-Table
             */
            db.execSQL("ALTER TABLE " + Task.TABLE + " RENAME TO tmp_tasks;");
            createTasksTableOLD(db);
            String cols = ModelBase.ID + ", " + Task.LIST_ID + ", " + ModelBase.NAME + ", "
                          + Task.DONE + ',' + Task.PRIORITY + ',' + Task.DUE + ','
                          + CREATED_AT + ',' + UPDATED_AT + ',' + SYNC_STATE_FIELD;
            db.execSQL("INSERT INTO " + Task.TABLE + " (" + cols + ") " + cols
                       + "FROM tmp_tasks;");
            db.execSQL("DROP TABLE tmp_tasks");
            db.execSQL("UPDATE " + Task.TABLE + " set " + Task.DUE
                       + "=null where " + Task.DUE + "='' OR " + Task.DUE
                       + "='null'");
            /*
             * Update Task-Table
             */
            db.execSQL("Alter Table " + ListMirakel.TABLE + " add column "
                       + ListMirakel.LFT + " INTEGER;");
            db.execSQL("Alter Table " + ListMirakel.TABLE + " add column "
                       + ListMirakel.RGT + " INTEGER;");
        case 5:
            createSpecialListsTable(db);
            db.execSQL("update " + ListMirakel.TABLE + " set "
                       + ListMirakel.LFT
                       + "=(select count(*) from (select * from "
                       + ListMirakel.TABLE + ") as a where a." + ModelBase.ID + '<'
                       + ListMirakel.TABLE + '.' + ModelBase.ID + ")*2 +1;");
            db.execSQL("update " + ListMirakel.TABLE + " set "
                       + ListMirakel.RGT + '=' + ListMirakel.LFT + "+1;");
        case 6:
            /*
             * Remove NOT NULL
             */
            db.execSQL("ALTER TABLE " + Task.TABLE + " RENAME TO tmp_tasks;");
            createTasksTableOLD(db);
            cols = ModelBase.ID + ", " + Task.LIST_ID + ", " + ModelBase.NAME + ", " + Task.DONE
                   + ',' + Task.PRIORITY + ',' + Task.DUE + ',' + CREATED_AT
                   + ',' + UPDATED_AT + ',' + SYNC_STATE_FIELD;
            db.execSQL("INSERT INTO " + Task.TABLE + " (" + cols + ") "
                       + "SELECT " + cols + "FROM tmp_tasks;");
            db.execSQL("DROP TABLE tmp_tasks");
            db.execSQL("UPDATE " + Task.TABLE + " set " + Task.DUE
                       + "=null where " + Task.DUE + "=''");
        case 7:
            /*
             * Add default list and default date for SpecialLists
             */
            db.execSQL("Alter Table " + SpecialList.TABLE + " add column "
                       + SpecialList.DEFAULT_LIST + " INTEGER;");
            db.execSQL("Alter Table " + SpecialList.TABLE + " add column "
                       + SpecialList.DEFAULT_DUE + " INTEGER;");
        case 8:
            /*
             * Add reminders for Tasks
             */
            db.execSQL("Alter Table " + Task.TABLE + " add column "
                       + Task.REMINDER + " INTEGER;");
        case 9:
            /*
             * Update Special Lists Table
             */
            db.execSQL("UPDATE special_lists SET " + SpecialList.DEFAULT_DUE
                       + "=0 where " + ModelBase.ID + "=2 and " + SpecialList.DEFAULT_DUE
                       + "=null");
            db.execSQL("UPDATE special_lists SET " + SpecialList.DEFAULT_DUE
                       + "=7 where " + ModelBase.ID + "=3 and " + SpecialList.DEFAULT_DUE
                       + "=null");
            db.execSQL("UPDATE special_lists SET " + SpecialList.DEFAULT_DUE
                       + "=-1, " + SpecialList.ACTIVE + "=0 where " + ModelBase.ID
                       + "=4 and " + SpecialList.DEFAULT_DUE + "=null");
        case 10:
            /*
             * Add UUID to Task
             */
            db.execSQL("Alter Table " + Task.TABLE + " add column " + Task.UUID
                       + " TEXT NOT NULL DEFAULT '';");
        // MainActivity.updateTasksUUID = true; TODO do we need this
        // anymore?
        // Don't remove this version-gap
        case 13:
            db.execSQL("Alter Table " + Task.TABLE + " add column "
                       + Task.ADDITIONAL_ENTRIES + " TEXT NOT NULL DEFAULT '';");
        case 14:// Add Sematic
            db.execSQL("CREATE TABLE " + Semantic.TABLE + " (" + ModelBase.ID
                       + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                       + "condition TEXT NOT NULL, " + "due INTEGER, "
                       + "priority INTEGER, " + "list INTEGER);");
            db.execSQL("INSERT INTO semantic_conditions (condition,due) VALUES "
                       + "(\""
                       + this.context.getString(R.string.today).toLowerCase(
                           Helpers.getLocal(this.context))
                       + "\",0);"
                       + "INSERT INTO semantic_conditions (condition,due) VALUES (\""
                       + this.context.getString(R.string.tomorrow).toLowerCase(
                           Helpers.getLocal(this.context)) + "\",1);");
        case 15:// Add Color
            db.execSQL("Alter Table " + ListMirakel.TABLE + " add column "
                       + ListMirakel.COLOR + " INTEGER;");
            db.execSQL("Alter Table " + SpecialList.TABLE + " add column "
                       + ListMirakel.COLOR + " INTEGER;");
        case 16:// Add File
            createFileTable(db);
        case 17:// Add Subtask
            createSubtaskTable(db);
        case 18:// Modify Semantic
            db.execSQL("ALTER TABLE " + Semantic.TABLE
                       + " add column default_list" + ModelBase.ID + " INTEGER");
            db.execSQL("update semantic_conditions SET condition=LOWER(condition);");
        case 19:// Make Specialist sortable
            db.execSQL("ALTER TABLE " + SpecialList.TABLE + " add column  "
                       + ListMirakel.LFT + " INTEGER;");
            db.execSQL("ALTER TABLE " + SpecialList.TABLE + " add column  "
                       + ListMirakel.RGT + " INTEGER ;");
            db.execSQL("update " + SpecialList.TABLE + " set "
                       + ListMirakel.LFT
                       + "=(select count(*) from (select * from "
                       + SpecialList.TABLE + ") as a where a." + ModelBase.ID + '<'
                       + SpecialList.TABLE + '.' + ModelBase.ID + ")*2 +1;");
            db.execSQL("update " + SpecialList.TABLE + " set "
                       + ListMirakel.RGT + '=' + ListMirakel.LFT + "+1;");
        case 20:// Add Recurring
            db.execSQL("CREATE TABLE " + Recurring.TABLE + " (" + ModelBase.ID
                       + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                       + "years INTEGER DEFAULT 0," + "months INTEGER DEFAULT 0,"
                       + "days INTEGER DEFAULT 0," + "hours INTEGER DEFAULT 0,"
                       + "minutes INTEGER DEFAULT 0,"
                       + "for_due INTEGER DEFAULT 0," + "label STRING);");
            db.execSQL("ALTER TABLE " + Task.TABLE + " add column "
                       + Task.RECURRING + " INTEGER DEFAULT '-1';");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(days,label,for_due) VALUES (1,'"
                       + this.context.getString(R.string.daily) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(days,label,for_due) VALUES (2,'"
                       + this.context.getString(R.string.second_day) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(days,label,for_due) VALUES (7,'"
                       + this.context.getString(R.string.weekly) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(days,label,for_due) VALUES (14,'"
                       + this.context.getString(R.string.two_weekly) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(months,label,for_due) VALUES (1,'"
                       + this.context.getString(R.string.monthly) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(years,label,for_due) VALUES (1,'"
                       + this.context.getString(R.string.yearly) + "',1);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(hours,label,for_due) VALUES (1,'"
                       + this.context.getString(R.string.hourly) + "',0);");
            db.execSQL("INSERT INTO " + Recurring.TABLE
                       + "(minutes,label,for_due) VALUES (1,'"
                       + this.context.getString(R.string.minutly) + "',0);");
        case 21:
            db.execSQL("ALTER TABLE " + Task.TABLE + " add column "
                       + Task.RECURRING_REMINDER + " INTEGER DEFAULT '-1';");
        case 22:
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column start_date String;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column end_date String;");
        case 23:
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column temporary int NOT NULL default 0;");
        // Add Accountmanagment
        case 24:
            createAccountTable(db);
            ACCOUNT_TYPES type = ACCOUNT_TYPES.LOCAL;
            AccountManager am = AccountManager.get(this.context);
            String accountname = this.context.getString(R.string.local_account);
            if (am.getAccountsByType(AccountMirakel.ACCOUNT_TYPE_MIRAKEL).length > 0) {
                final Account a = am
                                  .getAccountsByType(AccountMirakel.ACCOUNT_TYPE_MIRAKEL)[0];
                final String t = AccountManager.get(this.context).getUserData(
                                     a, DefinitionsHelper.BUNDLE_SERVER_TYPE);
                if (t.equals(DefinitionsHelper.TYPE_TW_SYNC)) {
                    type = ACCOUNT_TYPES.TASKWARRIOR;
                    accountname = a.name;
                }
            }
            ContentValues cv = new ContentValues();
            cv.put(ModelBase.NAME, accountname);
            cv.put(AccountMirakel.TYPE, type.toInt());
            cv.put(AccountMirakel.ENABLED, true);
            final long accountId = db.insert(AccountMirakel.TABLE, null, cv);
            db.execSQL("ALTER TABLE " + ListMirakel.TABLE + " add column "
                       + ListMirakel.ACCOUNT_ID + " REFERENCES "
                       + AccountMirakel.TABLE + " (" + ModelBase.ID
                       + ") ON DELETE CASCADE ON UPDATE CASCADE DEFAULT "
                       + accountId + "; ");
        // add progress
        case 25:
            db.execSQL("ALTER TABLE " + Task.TABLE
                       + " add column progress int NOT NULL default 0;");
        // Add some columns for caldavsync
        case 26:
            createCalDavExtraTable(db);
        case 27:
            db.execSQL("UPDATE " + Task.TABLE + " SET " + Task.PROGRESS
                       + "=100 WHERE " + Task.DONE + "= 1 AND " + Task.RECURRING
                       + "=-1");
        case 28:
            db.execSQL("ALTER TABLE " + Semantic.TABLE
                       + " add column weekday int;");
            final String[] weekdays = this.context.getResources()
                                      .getStringArray(R.array.weekdays);
            for (int i = 1; i < weekdays.length; i++) { // Ignore first element
                db.execSQL("INSERT INTO " + Semantic.TABLE + " ("
                           + Semantic.CONDITION + ',' + Semantic.WEEKDAY
                           + ") VALUES (?, " + i + ')',
                           new String[] { weekdays[i] });
            }
        // add some options to reccuring
        case 29:
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column isExact INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column monday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column tuesday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column wednesday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column thursday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column friday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column saturday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column sunnday INTEGER DEFAULT 0;");
            db.execSQL("ALTER TABLE " + Recurring.TABLE
                       + " add column derived_from INTEGER DEFAULT NULL");
        // also save the time of a due-date
        case 30:
            db.execSQL("UPDATE " + Task.TABLE + " set " + Task.DUE + '='
                       + Task.DUE + "||' 00:00:00'");
        // save all times in tasktable as utc-unix-seconds
        case 31:
            updateTimesToUTC(db);
        // move tw-sync-key to db
        // move tw-certs into accountmanager
        case 32:
            db.execSQL("ALTER TABLE " + AccountMirakel.TABLE + " add column "
                       + AccountMirakel.SYNC_KEY + " STRING DEFAULT '';");
            String ca = null,
                   client = null,
                   clientKey = null;
            final File caCert = new File(FileUtils.getMirakelDir()
                                         + "ca.cert.pem");
            final File userCert = new File(FileUtils.getMirakelDir()
                                           + "client.cert.pem");
            final File userKey = new File(FileUtils.getMirakelDir()
                                          + "client.key.pem");
            try {
                ca = FileUtils.readFile(caCert);
                client = FileUtils.readFile(userCert);
                clientKey = FileUtils.readFile(userKey);
                caCert.delete();
                userCert.delete();
                userKey.delete();
            } catch (final IOException e) {
                Log.wtf(TAG, "ca-files not found", e);
            }
            final AccountManager accountManager = AccountManager
                                                  .get(this.context);
            Cursor c = db.query(AccountMirakel.TABLE,
                                AccountMirakel.allColumns, null, null, null, null, null);
            final List<AccountMirakel> accounts = AccountMirakel.cursorToAccountList(c);
            c.close();
            for (final AccountMirakel a : accounts) {
                if (a.getType() == ACCOUNT_TYPES.TASKWARRIOR) {
                    final Account account = a.getAndroidAccount(this.context);
                    if (account == null) {
                        db.delete(AccountMirakel.TABLE, ModelBase.ID + "=?",
                                  new String[] {String.valueOf(a.getId())});
                        continue;
                    }
                    a.setSyncKey(fromNullable(accountManager.getPassword(account)));
                    db.update(AccountMirakel.TABLE, a.getContentValues(), ModelBase.ID
                              + "=?", new String[] {String.valueOf(a.getId())});
                    if ((ca != null) && (client != null) && (clientKey != null)) {
                        accountManager.setUserData(account,
                                                   DefinitionsHelper.BUNDLE_CERT, ca);
                        accountManager.setUserData(account,
                                                   DefinitionsHelper.BUNDLE_CERT_CLIENT, client);
                        accountManager.setUserData(account,
                                                   DefinitionsHelper.BUNDLE_KEY_CLIENT, clientKey);
                    }
                }
            }
        case 33:
            db.execSQL("UPDATE " + SpecialList.TABLE + " SET "
                       + SpecialList.WHERE_QUERY + "=replace("
                       + SpecialList.WHERE_QUERY
                       + ",'date(due',\"date(due,'unixepoch'\")");
        case 34:
            Cursor cursor = db.query(SpecialList.TABLE, new String[] { ModelBase.ID,
                                     SpecialList.WHERE_QUERY
                                                                     }, null, null, null, null, null);
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor
                 .moveToNext()) {
                final int id = cursor.getInt(0);
                final ContentValues contentValues = new ContentValues();
                final String[] where = cursor.getString(1).toLowerCase()
                                       .split("and");
                final Map<String, SpecialListsBaseProperty> whereMap = new HashMap<>(where.length);
                for (final String p : where) {
                    try {
                        if (p.contains(Task.LIST_ID)) {
                            whereMap.put(Task.LIST_ID, CompatibilityHelper
                                         .getSetProperty(p,
                                                         SpecialListsListProperty.class,
                                                         Task.LIST_ID));
                        } else if (p.contains(ModelBase.NAME)) {
                            whereMap.put(ModelBase.NAME,
                                         CompatibilityHelper.getStringProperty(p,
                                                 SpecialListsNameProperty.class,
                                                 ModelBase.NAME));
                        } else if (p.contains(Task.PRIORITY)) {
                            whereMap.put(Task.PRIORITY, CompatibilityHelper
                                         .getSetProperty(p,
                                                         SpecialListsPriorityProperty.class,
                                                         Task.PRIORITY));
                        } else if (p.contains(Task.DONE)) {
                            whereMap.put(Task.DONE,
                                         CompatibilityHelper.getDoneProperty(p));
                        } else if (p.contains(Task.DUE)) {
                            whereMap.put(Task.DUE,
                                         CompatibilityHelper.getDueProperty(p));
                        } else if (p.contains(Task.CONTENT)) {
                            whereMap.put(Task.CONTENT, CompatibilityHelper
                                         .getStringProperty(p,
                                                            SpecialListsContentProperty.class,
                                                            Task.CONTENT));
                        } else if (p.contains(Task.REMINDER)) {
                            whereMap.put(Task.REMINDER,
                                         CompatibilityHelper.getReminderProperty(p));
                        }
                    } catch (final TransformerException e) {
                        Log.w(TAG, "due cannot be transformed", e);
                    }
                }
                contentValues.put(SpecialList.WHERE_QUERY,
                                  CompatibilityHelper.serializeWhereSpecialLists(whereMap));
                db.update(SpecialList.TABLE, contentValues, ModelBase.ID + "=?",
                          new String[] {String.valueOf(id)});
            }
            cursor.close();
        case 35:
            am = AccountManager.get(this.context);
            for (final Account a : am
                 .getAccountsByType(AccountMirakel.ACCOUNT_TYPE_MIRAKEL)) {
                clientKey = am.getUserData(a,
                                           DefinitionsHelper.BUNDLE_KEY_CLIENT);
                if ((clientKey != null) && !clientKey.trim().isEmpty()) {
                    am.setPassword(a, clientKey
                                   + "\n:" + am.getPassword(a));
                }
            }
        case 36:
            cursor = db.query(FileMirakel.TABLE,
                              new String[] { "_id", "path" }, null, null, null, null,
                              null);
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    final File f = new File(cursor.getString(1));
                    if (f.exists()) {
                        cv = new ContentValues();
                        cv.put("path", Uri.fromFile(f).toString());
                        db.update(FileMirakel.TABLE, cv, "_id=?",
                                  new String[] { cursor.getString(0) });
                    } else {
                        db.delete(FileMirakel.TABLE, "_id=?",
                                  new String[] { cursor.getString(0) });
                    }
                } while (cursor.moveToNext());
                cursor.close();
            }
        case 37:
            // Introduce tags
            db.execSQL("CREATE TABLE " + Tag.TABLE + " (" + ModelBase.ID
                       + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ModelBase.NAME
                       + " TEXT NOT NULL, " + Tag.DARK_TEXT
                       + " INTEGER NOT NULL DEFAULT 0, " + "color_a"
                       + " INTEGER NOT NULL DEFAULT 0, " + "color_b"
                       + " INTEGER NOT NULL DEFAULT 0, " + "color_g"
                       + " INTEGER NOT NULL DEFAULT 0, " + "color_r"
                       + " INTEGER NOT NULL DEFAULT 0);");
            db.execSQL("CREATE TABLE "
                       + Tag.TAG_CONNECTION_TABLE
                       + " ("
                       + ModelBase.ID
                       + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                       + " task_id INTEGER REFERENCES "
                       + Task.TABLE
                       + " ("
                       + ModelBase.ID
                       + ") "
                       + "ON DELETE CASCADE ON UPDATE CASCADE,tag_id INTEGER REFERENCES "
                       + Tag.TABLE + " (" + ModelBase.ID
                       + ") ON DELETE CASCADE ON UPDATE CASCADE);");
            cursor = db.query(Task.TABLE, new String[] { "_id",
                              "additional_entries"
                                                       },
                              "additional_entries LIKE '%\"tags\":[\"%'", null, null,
                              null, null);
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int count = 0;
                do {
                    final int taskId = cursor.getInt(0);
                    final Map<String, String> entryMap = Task
                                                         .parseAdditionalEntries(cursor.getString(1));
                    String entries = entryMap.get("tags").trim();
                    entries = entries.replace("[", "");
                    entries = entries.replace("]", "");
                    entries = entries.replace("\"", "");
                    final String[] tags = entries.split(",");
                    for (final String tag : tags) {
                        c = db.query(Tag.TABLE, new String[] { ModelBase.ID }, ModelBase.NAME
                                     + "=?", new String[] { tag }, null, null, null);
                        final int tagId;
                        if (c.getCount() > 0) {
                            c.moveToFirst();
                            tagId = c.getInt(0);
                        } else {
                            // create tag;
                            final int color = Tag.getNextColor(count++,
                                                               this.context);
                            cv = new ContentValues();
                            cv.put(ModelBase.NAME, tag);
                            cv.put("color_r", Color.red(color));
                            cv.put("color_g", Color.green(color));
                            cv.put("color_b", Color.blue(color));
                            cv.put("color_a", Color.alpha(color));
                            tagId = (int) db.insert(Tag.TABLE, null, cv);
                        }
                        cv = new ContentValues();
                        cv.put("tag_id", tagId);
                        cv.put("task_id", taskId);
                        db.insert(Tag.TAG_CONNECTION_TABLE, null, cv);
                        entryMap.remove("tags");
                        cv = new ContentValues();
                        cv.put(Task.ADDITIONAL_ENTRIES,
                               Task.serializeAdditionalEntries(entryMap));
                        db.update(Task.TABLE, cv, ModelBase.ID + "=?",
                                  new String[] {String.valueOf(taskId)});
                    }
                } while (cursor.moveToNext());
            }
            if (!DefinitionsHelper.freshInstall) {
                final List<Integer> parts = MirakelCommonPreferences
                                            .loadIntArray("task_fragment_adapter_settings");
                parts.add(8);// hardcode tags, because of dependencies
                MirakelCommonPreferences.saveIntArray(
                    "task_fragment_adapter_settings", parts);
            }
        // refactor recurrence to follow the taskwarrior method
        case 38:
            createTableRecurrenceTW(db);
        case 39:
            db.execSQL("ALTER TABLE " + Task.TABLE + " add column "
                       + Task.RECURRING_SHOWN + " INTEGER DEFAULT 1;");
            c = db.query(Task.TABLE, Task.allColumns,
                         "additional_entries LIKE ?",
                         new String[] { "%\"status\":\"recurring\"%" }, null, null,
                         null);
            final Map<Task, List<Task>> recurring = new HashMap<>(c.getCount());
            for (c.moveToFirst(); c.moveToNext();) {
                final Task t = new Task(c);
                final String recurString = t.getAdditionalString("recur");
                if (recurString == null) {
                    continue;
                }
                // check if is childtask
                if (t.existAdditional("parent")) {
                    final Optional<Task> masterOptional = Task.getByUUID(t
                                                          .getAdditionalString("parent"));

                    if (masterOptional.isPresent()) {
                        final Task master = masterOptional.get();
                        final List<Task> list;
                        if (recurring.containsKey(master)) {
                            list = recurring.get(master);
                        } else {
                            list = new ArrayList<>(1);
                        }
                        list.add(t);
                        recurring.put(master, list);
                    }
                } else if (!recurring.containsKey(t)) {// its recurring master
                    recurring.put(t, new ArrayList<Task>(0));
                }
                t.setRecurrence(CompatibilityHelper.parseTaskWarriorRecurrence(
                                    recurString).getId());
                t.save();
            }
            StringBuilder idsToHidde = new StringBuilder();
            boolean first = true;
            for (final Entry<Task, List<Task>> rec : recurring.entrySet()) {
                if (rec.getValue().isEmpty()) {
                    continue;
                }
                Task newest = rec.getValue().get(0);
                for (final Task t : rec.getValue()) {
                    cv = new ContentValues();
                    cv.put("parent", rec.getKey().getId());
                    cv.put("child", t.getId());
                    final int counter = t.getAdditionalInt("imask");
                    cv.put("offsetCount", counter);
                    final Optional<Recurring> recurringOptional = t.getRecurrence();
                    if (recurringOptional.isPresent()) {
                        cv.put("offset", counter * recurringOptional.get().getInterval());
                    } else {
                        continue;
                    }
                    db.insert(Recurring.TW_TABLE, null, cv);
                    final int newestOffset = newest.getAdditionalInt("imask");
                    final int currentOffset = t.getAdditionalInt("imask");
                    if (newestOffset < currentOffset) {
                        if (first) {
                            first = false;
                        } else {
                            idsToHidde.append(',');
                        }
                        idsToHidde.append(newest.getId());
                        newest = t;
                    }
                }
            }
            if (!idsToHidde.toString().isEmpty()) {
                cv = new ContentValues();
                cv.put(Task.RECURRING_SHOWN, false);
                db.update(Task.TABLE, cv, "_id IN (?)",
                          new String[] { idsToHidde.toString() });
            }
            c.close();
        case 40:
            // Update settings
            updateSettings();
            // Alter tag table
            db.execSQL("ALTER TABLE tag RENAME to tmp_tags;");
            db.execSQL("CREATE TABLE " + Tag.TABLE + " ("
                       + ModelBase.ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                       + ModelBase.NAME + " TEXT NOT NULL, "
                       + Tag.DARK_TEXT + " INTEGER NOT NULL DEFAULT 0, "
                       + Tag.BACKGROUND_COLOR + " INTEGER NOT NULL DEFAULT 0);");
            db.execSQL("INSERT INTO " + Tag.TABLE +
                       " (_id,name,dark_text) SELECT _id,name,dark_text FROM tmp_tags;");
            final String[] tagColumns = new String[] {"_id", "color_a", "color_r", "color_g", "color_b"};
            final Cursor tagCursor = db.query("tmp_tags", tagColumns, null, null, null, null, null);
            if (tagCursor.moveToFirst()) {
                do {
                    int i = 0;
                    final int id = tagCursor.getInt(i++);
                    final int rgba = tagCursor.getInt(i++);
                    final int rgbr = tagCursor.getInt(i++);
                    final int rgbg = tagCursor.getInt(i++);
                    final int rgbb = tagCursor.getInt(i);
                    final int newColor = Color.argb(rgba, rgbr, rgbg, rgbb);
                    final Cursor tagC = db.query(Tag.TABLE, Tag.allColumns, ModelBase.ID
                                                 + "=?", new String[] {String.valueOf(id)}, null, null, null);
                    if (tagC.moveToFirst()) {
                        final Tag newTag = new Tag(tagC);
                        tagC.close();
                        newTag.setBackgroundColor(newColor);
                        db.update(Tag.TABLE, newTag.getContentValues(), ModelBase.ID + "=?", new String[] {String.valueOf(newTag.getId())});
                    }
                } while (tagCursor.moveToNext());
            }
            db.execSQL("DROP TABLE tmp_tags;");
            db.execSQL("create unique index tag_unique ON tag (name);");
        case 41:
            updateCaldavExtra(db);
        case 42:
            updateListTable(db);
        case 43:
            db.execSQL("DROP VIEW caldav_lists;");
            db.execSQL("DROP VIEW caldav_tasks;");
            createCaldavListsTrigger(db);
            createCaldavTasksTrigger(db);
        case 44:
            createCaldavPropertyView(db);
        case 45:
            updateSpecialLists(db);
        case 46:
            db.execSQL("UPDATE " + Task.TABLE + " SET " + UPDATED_AT + " =strftime('%s','now') WHERE " +
                       UPDATED_AT + ">strftime('%s','now');");
        default:
            break;
        }
    }

    private void updateSpecialLists(final SQLiteDatabase db) {
        final Cursor updateSpecial = db.query(SpecialList.TABLE, new String[] {SpecialList.WHERE_QUERY, ModelBase.ID},
                                              null,
                                              null, null, null, null);
        while (updateSpecial.moveToNext()) {
            final String query = updateSpecial.getString(0);
            StringBuilder newQuery = new StringBuilder();
            int counter = 0;
            boolean isMultiPart = false;
            boolean isArgument = false;
            boolean lastIsBracket = false;
            for (int i = 0; i < query.length(); i++) {
                char p = query.charAt(i);
                switch (p) {
                case '"':
                    lastIsBracket = false;
                    isArgument = !isArgument;
                    newQuery.append(p);
                    break;
                case '{':
                    if (!lastIsBracket) {
                        newQuery.append(p);
                    }
                    if (!isArgument) {
                        if (!lastIsBracket) {
                            counter++;
                        }
                        lastIsBracket = true;
                    }
                    break;
                case '}':
                    lastIsBracket = false;
                    if (counter > 0) {
                        newQuery.append(p);
                        if (!isArgument) {
                            counter--;
                        }
                    }
                    break;
                case ',':
                    lastIsBracket = false;
                    if (DefinitionsHelper.freshInstall && (counter == 0)) {
                        isMultiPart = true;
                        newQuery.append(p);
                        break;
                    } else if (!DefinitionsHelper.freshInstall && (counter == 1)) {
                        isMultiPart = true;
                        newQuery.append('}').append(p).append('{');
                        break;
                    }
                default:
                    lastIsBracket = false;
                    newQuery.append(p);
                }
            }
            final ContentValues newWhere = new ContentValues();
            if (isMultiPart) {
                newQuery = new StringBuilder("[" + newQuery + ']');
            }
            newWhere.put(SpecialList.WHERE_QUERY, newQuery.toString());
            db.update(SpecialList.TABLE, newWhere, ModelBase.ID + "=?", new String[] {String.valueOf(updateSpecial.getLong(1))});
        }
    }



    private static void createCaldavListsTrigger(final SQLiteDatabase db) {
        db.execSQL("CREATE VIEW caldav_lists AS SELECT _sync_id, sync_version, CASE WHEN l.sync_state IN (-1,0) THEN 0 ELSE 1 END AS _dirty, sync1, sync2, sync3, sync4, sync5, sync6, sync7, sync8, a.name AS account_name, account_type, l._id, l.name AS list_name, l.color AS list_color, access_level, visible, "
                   +
                   "a.enabled AS sync_enabled, owner AS list_owner\n"
                   +
                   "FROM lists as l\n" +
                   "LEFT JOIN caldav_lists_extra ON l._id=list_id\n" +
                   "LEFT JOIN account AS a ON a._id = account_id;");
        // Create trigger for lists
        // Insert trigger
        db.execSQL("CREATE TRIGGER caldav_lists_insert_trigger INSTEAD OF INSERT ON caldav_lists\n" +
                   "BEGIN\n"
                   + "INSERT INTO account(name,type) SELECT new.account_name, " + ACCOUNT_TYPES.CALDAV.toInt() +
                   " WHERE NOT EXISTS(SELECT 1 FROM account WHERE name=new.account_name);"
                   + "INSERT INTO lists (sync_state, name, color, account_id,lft,rgt) VALUES (0, new.list_name, new.list_color, (SELECT DISTINCT _id FROM account WHERE name = new.account_name),(SELECT MAX(lft) from lists)+2,(SELECT MAX(rgt) from lists)+2);"
                   + "UPDATE account SET enabled=new.sync_enabled WHERE name = new.account_name;"
                   + "INSERT INTO caldav_lists_extra VALUES\n" +
                   "((SELECT last_insert_rowid() FROM lists),new._sync_id, new.sync_version, new.sync1, new.sync2, new.sync3, new.sync4, new.sync5, new.sync6, new.sync7, new.sync8, new.account_type , new.access_level, new.visible, new.sync_enabled, new.list_owner);\n"
                   +
                   "END;");
        db.execSQL("CREATE TRIGGER caldav_lists_update_trigger INSTEAD OF UPDATE on caldav_lists\n" +
                   "BEGIN\n" +
                   "UPDATE lists SET sync_state=0, name = new.list_name, color = new.list_color WHERE _id = old._id;\n"
                   + "UPDATE account SET enabled=new.sync_enabled WHERE name = new.account_name;"
                   + "INSERT OR REPLACE INTO caldav_lists_extra VALUES (new._id, new._sync_id, new.sync_version, new.sync1, new.sync2, new.sync3, new.sync4, new.sync5, new.sync6, new.sync7, new.sync8, new.account_type , new.access_level, new.visible, new.sync_enabled, new.list_owner);\n"
                   +
                   "END;");
        db.execSQL("CREATE TRIGGER caldav_lists_delete_trigger INSTEAD OF DELETE on caldav_lists\n" +
                   "BEGIN\n" +
                   "    DELETE FROM lists WHERE _id = old._id;\n" +
                   "END;\n");
    }

    private void updateListTable(final SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + ListMirakel.TABLE + " RENAME TO tmp_lists;");
        db.execSQL("CREATE TABLE " + ListMirakel.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + ModelBase.NAME
                   + " TEXT NOT NULL, " + ListMirakel.SORT_BY_FIELD
                   + " INTEGER NOT NULL DEFAULT 0, " + CREATED_AT
                   + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, " + UPDATED_AT
                   + " INTEGER NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                   + SYNC_STATE_FIELD + " INTEGER DEFAULT " + SYNC_STATE.ADD
                   + ", " + ListMirakel.LFT + " INTEGER, " + ListMirakel.RGT
                   + " INTEGER " + ", " + ListMirakel.COLOR + " INTEGER,"
                   + ListMirakel.ACCOUNT_ID + " INTEGER REFERENCES "
                   + AccountMirakel.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE "
                   + ')');
        db.execSQL("INSERT INTO " + ListMirakel.TABLE + " SELECT * FROM tmp_lists");
        db.execSQL("DROP TABLE tmp_lists;");
    }


    private void updateSettings() {
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        settingsIntToLong(settings, "defaultAccountID");
        settingsIntToLong(settings, "subtaskAddToList");
    }
    private void settingsIntToLong(final SharedPreferences settings, final String key) {
        try {
            final int i = settings.getInt(key, -1);
            if (i != -1) {
                final SharedPreferences.Editor editor = settings.edit();
                editor.putLong(key, Long.valueOf(i));
                editor.commit();
            }
        } catch (final ClassCastException e) {
            Log.i(TAG, "The setting was already a long", e);
        }
    }



    private static void createCaldavLists(final SQLiteDatabase db) {
        // Create table for extras for lists
        db.execSQL("CREATE TABLE caldav_lists_extra (\n" +
                   "list_id INTEGER PRIMARY KEY REFERENCES lists(_id) ON DELETE CASCADE ON UPDATE CASCADE,\n" +
                   "_sync_id TEXT,\n" +
                   "sync_version TEXT,\n" +
                   "sync1 TEXT,\n" +
                   "sync2 TEXT,\n" +
                   "sync3 TEXT,\n" +
                   "sync4 TEXT,\n" +
                   "sync5 TEXT,\n" +
                   "sync6 TEXT,\n" +
                   "sync7 TEXT,\n" +
                   "sync8 TEXT,\n" +
                   "account_type TEXT,\n" +
                   "access_level INTEGER,\n" +
                   "visible INTEGER,\n" +
                   "sync_enabled INTEGER,\n" +
                   "owner TEXT);");
        createCaldavListsTrigger(db);
    }


    private static void createCaldavTasks(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE caldav_tasks_extra (\n" +
                   "_sync_id TEXT,\n" +
                   "sync_version TEXT,\n" +
                   "sync1 TEXT,\n" +
                   "sync2 TEXT,\n" +
                   "sync3 TEXT,\n" +
                   "sync4 TEXT,\n" +
                   "sync5 TEXT,\n" +
                   "sync6 TEXT,\n" +
                   "sync7 TEXT,\n" +
                   "sync8 TEXT,\n" +
                   "_uid TEXT,\n" +
                   "task_id INTEGER PRIMARY KEY REFERENCES tasks(_id) ON DELETE CASCADE ON UPDATE CASCADE,\n" +
                   "location TEXT,\n" +
                   "geo TEXT,\n" +
                   "url TEXT,\n" +
                   "organizer TEXT,\n" +
                   "priority INTEGER,\n" +
                   "classification INTEGER,\n" +
                   "completed_is_allday DEFAULT 0,\n" +
                   "status INTEGER,\n" +
                   "task_color INTEGER,\n" +
                   "dtstart INTEGER,\n" +
                   "is_allday INTEGER,\n" +
                   "tz TEXT,\n" +
                   "duration TEXT,\n" +
                   "rdate TEXT,\n" +
                   "exdate TEXT,\n" +
                   "rrule TEXT,\n" +
                   "original_instance_sync_id INTEGER,\n" +
                   "original_instance_id INTEGER,\n" +
                   "original_instance_time INTEGER,\n" +
                   "original_instance_allday INTEGER,\n" +
                   "parent_id INTEGER,\n" +
                   "sorting TEXT,\n" +
                   "has_alarms INTEGER);");
        createCaldavTasksTrigger(db);
    }

    private static void createCaldavTasksTrigger(final SQLiteDatabase db) {
        // View
        db.execSQL("CREATE VIEW caldav_tasks AS SELECT \n" +
                   "e._sync_id,\n" +
                   "e.sync_version,\n" +
                   "CASE WHEN t.sync_state IN (-1,0) THEN 0 ELSE 1 END _dirty,\n" +
                   "e.sync1,\n" +
                   "e.sync2,\n" +
                   "e.sync3,\n" +
                   "e.sync4,\n" +
                   "e.sync5,\n" +
                   "e.sync6,\n" +
                   "e.sync7,\n" +
                   "e.sync8,\n" +
                   "e._uid,\n" +
                   "CASE WHEN t.sync_state = -1 THEN 1 ELSE 0 END _deleted,\n" +
                   "t._id,\n" +
                   "t.list_id,\n" +
                   "t.name as title,\n" +
                   "e.location,\n" +
                   "e.geo,\n" +
                   "t.content as description,\n" +
                   "e.url,\n" +
                   "e.organizer,\n" +
                   "CASE \n" +
                   "     WHEN t.priority<0 THEN\n" +
                   "         CASE WHEN e.priority BETWEEN 7 AND 9 THEN e.priority ELSE 9 END\n"
                   +
                   "     WHEN t.priority=1 THEN\n" +
                   "         CASE WHEN e.priority BETWEEN 4 AND 6 THEN e.priority ELSE 5 END\n"
                   +
                   "     WHEN t.priority=2 THEN\n" +
                   "         CASE WHEN e.priority BETWEEN 1 AND 3 THEN e.priority ELSE 1 END\n"
                   +
                   "     ELSE 0\n" +
                   "END AS priority,\n" +
                   "e.classification,\n" +
                   "CASE WHEN t.done=1 THEN t.updated_at ELSE null END AS completed,\n" +
                   "e.completed_is_allday,\n" +
                   "t.progress AS percent_complete,\n" +
                   "CASE\n" +
                   "     WHEN t.done = 1 THEN \n" +
                   "         CASE WHEN e.status IN (2,3) THEN e.status ELSE 2 END\n" +
                   "     WHEN t.progress>0 AND NOT t.done=1 THEN 1\n" +
                   "     ELSE \n" +
                   "         CASE WHEN e.status IN(0,1) THEN e.status ELSE 0 END\n" +
                   "END AS status,\n" +
                   "CASE \n" + "" +
                   "     WHEN t.done = 0 AND t.progress=0 THEN 1 \n" +
                   "     ELSE CASE WHEN status = 0 AND NOT t.done=1 THEN 1 ELSE 0 END\n" +
                   "END AS is_new,\n" +
                   "CASE \n" +
                   "     WHEN done=1 THEN 1\n" +
                   "     ELSE CASE WHEN (status=3 OR status=2) AND NOT t.done=0 THEN 1 ELSE 0 END\n " +
                   "END AS is_closed,\n" +
                   "task_color,\n" +
                   "e.dtstart,\n" +
                   "e.is_allday,\n" +
                   "t.created_at * 1000 AS created,\n" +
                   "t.updated_at * 1000 AS last_modified,\n" +
                   "e.tz,\n" +
                   "t.due * 1000 AS due,\n" +
                   "e.duration,\n" +
                   "e.rdate,\n" +
                   "e.exdate,\n" +
                   "e.rrule,\n" +
                   "e.original_instance_sync_id,\n" +
                   "e.original_instance_id,\n" +
                   "e.original_instance_time,\n" +
                   "e.original_instance_allday,\n" +
                   "e.parent_id,\n" +
                   "e.sorting,\n" +
                   "e.has_alarms,\n" +
                   "l.account_name,\n" +
                   "l.account_type,\n" +
                   "l.list_name,\n" +
                   "l.list_color,\n" +
                   "l.list_owner AS list_owner,\n" +
                   "l.access_level AS list_access_level,\n" +
                   "l.visible\n" +
                   "FROM\n" +
                   "tasks AS t\n" +
                   "LEFT JOIN caldav_tasks_extra as e ON task_id = t._id\n" +
                   "INNER JOIN caldav_lists as l ON l._id = t.list_id;");
        // Insert trigger
        db.execSQL("CREATE TRIGGER caldav_tasks_insert_trigger INSTEAD OF INSERT ON caldav_tasks\n" +
                   "BEGIN\n" +
                   "    INSERT INTO tasks (sync_state, list_id, name, content, progress, done, due, priority, created_at, updated_at) VALUES (\n"
                   +
                   "    0,\n" +
                   "    new.list_id,\n" +
                   "    new.title,\n" +
                   "    new.description,\n" +
                   "    new.percent_complete,\n" +
                   "    CASE WHEN new.status IN(2,3) THEN 1 ELSE 0 END,  \n" +
                   "    new.due / 1000,\n" +
                   "    CASE WHEN new.priority=0 THEN 0\n" +
                   "         WHEN new.priority < 4 THEN 2 \n" +
                   "         WHEN new.priority < 7 THEN 1 \n" +
                   "         WHEN new.priority <= 9 THEN -1 \n" +
                   "    ELSE 0\n" +
                   "    END,\n" +
                   "    new.created / 1000,\n" +
                   "    new.last_modified / 1000);\n" +
                   "    INSERT INTO caldav_tasks_extra (task_id,_sync_id,location,geo,url,organizer,priority,classification, completed_is_allday,"
                   +
                   "    status, task_color, dtstart, is_allday, tz, duration, rdate, exdate, rrule, original_instance_sync_id, "
                   +
                   "    original_instance_id, original_instance_time, original_instance_allday, parent_id, sorting, has_alarms,"
                   +
                   "    sync1, sync2, sync3, sync4, sync5, sync6, sync7, sync8)\n"
                   +
                   "    VALUES\n" +
                   "    ((SELECT last_insert_rowid() FROM tasks),new._sync_id, new.location, new.geo, new.url, new.organizer, "
                   +
                   "    new.priority, new.classification, new.completed_is_allday, new.status, new.task_color, new.dtstart, new.is_allday, "
                   +
                   "    new.tz, new.duration, new.rdate, new.exdate, new.rrule, new.original_instance_sync_id, new.original_instance_id, "
                   +
                   "    new.original_instance_time, new.original_instance_allday, new.parent_id, new.sorting, new.has_alarms,"
                   +
                   "    new.sync1, new.sync2, new.sync3, new.sync4, new.sync5, new.sync6, new.sync7, new.sync8);\n"
                   +
                   "END;");
        // Update trigger
        db.execSQL("CREATE TRIGGER caldav_tasks_update_trigger INSTEAD OF UPDATE ON caldav_tasks\n" +
                   "BEGIN\n" +
                   "UPDATE tasks SET\n" +
                   "sync_state = 0,\n" +
                   "list_id = new.list_id,\n" +
                   "name = new.title,\n" +
                   "content = new.description,\n" +
                   "progress = new.percent_complete,\n" +
                   "done = CASE WHEN new.status IN(2,3) THEN 1 ELSE 0 END,    \n" +
                   "due = new.due / 1000,\n" +
                   "priority = CASE WHEN new.priority=0 THEN 0\n" +
                   "                WHEN new.priority < 4 THEN 2 \n" +
                   "                WHEN new.priority < 7 THEN 1 \n" +
                   "                WHEN new.priority <= 9 THEN -1 \n" +
                   "                ELSE 0\n" +
                   "END,\n" +
                   "updated_at = new.last_modified / 1000\n" +
                   "WHERE _id = old._id;\n" +
                   "INSERT OR REPLACE INTO caldav_tasks_extra VALUES (\n" +
                   "new._sync_id,\n" +
                   "new.sync_version,\n" +
                   "new.sync1,\n" +
                   "new.sync2,\n" +
                   "new.sync3,\n" +
                   "new.sync4,\n" +
                   "new.sync5,\n" +
                   "new.sync6,\n" +
                   "new.sync7,\n" +
                   "new.sync8,\n" +
                   "new._uid,\n" +
                   "new._id,\n" +
                   "new.location,\n" +
                   "new.geo,\n" +
                   "new.url,\n" +
                   "new.organizer,\n" +
                   "new.priority,\n" +
                   "new.classification,\n" +
                   "new.completed_is_allday,\n" +
                   "new.status,\n" +
                   "new.task_color,\n" +
                   "new.dtstart,\n" +
                   "new.is_allday,\n" +
                   "new.tz,\n" +
                   "new.duration,\n" +
                   "new.rdate,\n" +
                   "new.exdate,\n" +
                   "new.rrule,\n" +
                   "new.original_instance_sync_id,\n" +
                   "new.original_instance_id,\n" +
                   "new.original_instance_time,\n" +
                   "new.original_instance_allday,\n" +
                   "new.parent_id,\n" +
                   "new.sorting,\n" +
                   "new.has_alarms);\n" +
                   "END;");
        // Delete Trigger
        db.execSQL("CREATE TRIGGER caldav_tasks_delete_trigger INSTEAD OF DELETE ON caldav_tasks\n" +
                   "BEGIN\n" +
                   "    DELETE FROM tasks WHERE _id=old._id;\n" +
                   "    DELETE FROM caldav_tasks_extra WHERE task_id=old._id;\n" +
                   "END;");
    }

    private static void createCaldavProperties(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE caldav_properties (\n" +
                   "property_id INTEGER,\n" +
                   "task_id INTEGER REFERENCES tasks(_id) ON DELETE CASCADE ON UPDATE CASCADE,\n" +
                   "mimetype TEXT,\n" +
                   "prop_version TEXT,\n" +
                   "prop_sync1 TEXT,\n" +
                   "prop_sync2 TEXT,\n" +
                   "prop_sync3 TEXT,\n" +
                   "prop_sync4 TEXT,\n" +
                   "prop_sync5 TEXT,\n" +
                   "prop_sync6 TEXT,\n" +
                   "prop_sync7 TEXT,\n" +
                   "prop_sync8 TEXT,\n" +
                   "data0  TEXT,\n" +
                   "data1  TEXT,\n" +
                   "data2  TEXT,\n" +
                   "data3  TEXT,\n" +
                   "data4  TEXT,\n" +
                   "data5  TEXT,\n" +
                   "data6  TEXT,\n" +
                   "data7  TEXT,\n" +
                   "data8  TEXT,\n" +
                   "data9  TEXT,\n" +
                   "data10 TEXT,\n" +
                   "data11 TEXT,\n" +
                   "data12 TEXT,\n" +
                   "data13 TEXT,\n" +
                   "data14 TEXT,\n" +
                   "data15 TEXT,\n" +
                   "PRIMARY KEY (property_id, task_id)\n" +
                   ");");
    }

    private static void createCaldavPropertyView(final SQLiteDatabase db) {
        db.execSQL("DROP VIEW IF EXISTS caldav_property_view;");
        db.execSQL("CREATE VIEW caldav_property_view AS\n" +
                   "SELECT\n" +
                   "property_id,\n" +
                   "task_id,\n" +
                   "mimetype,\n" +
                   "prop_version,\n" +
                   "prop_sync1,\n" +
                   "prop_sync2,\n" +
                   "prop_sync3,\n" +
                   "prop_sync4,\n" +
                   "prop_sync5,\n" +
                   "prop_sync6,\n" +
                   "prop_sync7,\n" +
                   "prop_sync8,\n" +
                   "data0,\n" +
                   "data1,\n" +
                   "data2,\n" +
                   "data3,\n" +
                   "data4,\n" +
                   "data5,\n" +
                   "data6,\n" +
                   "data7,\n" +
                   "data8,\n" +
                   "data9,\n" +
                   "data10,\n" +
                   "data11,\n" +
                   "data12,\n" +
                   "data13,\n" +
                   "data14,\n" +
                   "data15\n" +
                   "FROM caldav_properties\n" +
                   "UNION\n" +
                   "SELECT \n" +
                   "(SELECT MAX(property_id) FROM caldav_properties)+tag._id AS property_id,\n" +
                   "task.task_id AS task_id,\n" +
                   "'vnd.android.cursor.item/category' AS mimetype,\n" +
                   "0 AS prop_version,\n" +
                   "null AS prop_sync1,\n" +
                   "null AS prop_sync2,\n" +
                   "null AS prop_sync3,\n" +
                   "null AS prop_sync4,\n" +
                   "null AS prop_sync5,\n" +
                   "null AS prop_sync6,\n" +
                   "null AS prop_sync7,\n" +
                   "null AS prop_sync8,\n" +
                   "tag._id AS data0,\n" +
                   "tag.name AS data1,\n" +
                   "tag.color AS data2,\n" +
                   "null AS data3,\n" +
                   "null AS data4,\n" +
                   "null AS data5,\n" +
                   "null AS data6,\n" +
                   "null AS data7,\n" +
                   "null AS data8,\n" +
                   "null AS data9,\n" +
                   "null AS data10,\n" +
                   "null AS data11,\n" +
                   "null AS data12,\n" +
                   "null AS data13,\n" +
                   "null AS data14,\n" +
                   "null AS data15\n" +
                   "FROM tag AS TAG\n" +
                   "INNER JOIN task_tag as task ON tag._id=task.tag_id\n" +
                   ';');
        db.execSQL("Create TRIGGER caldav_property_insert_tag_trigger INSTEAD OF INSERT ON caldav_property_view\n"
                   +
                   "WHEN new.mimetype = 'vnd.android.cursor.item/category'\n" +
                   "BEGIN\n" +
                   "\tINSERT OR REPLACE INTO tag (name,color) VALUES (new.data1, new.data2);\n" +
                   "\tINSERT OR REPLACE INTO task_tag(task_id,tag_id) VALUES(new.task_id,(SELECT _id FROM tag WHERE name=new.data1 AND color=new.data2));\n"
                   +
                   "END;");
        db.execSQL("Create TRIGGER caldav_property_insert_other_trigger INSTEAD OF INSERT ON caldav_property_view\n"
                   +
                   "WHEN NOT new.mimetype = 'vnd.android.cursor.item/category'\n" +
                   "BEGIN\n" +
                   "\tINSERT OR REPLACE INTO caldav_properties (property_id, task_id, mimetype, prop_version, prop_sync1, prop_sync2, prop_sync3, prop_sync4, prop_sync5, prop_sync6, prop_sync7, prop_sync8, data0, data1, data2, data3, data4, data5, data6, data7, data8, data9, data10, data11, data12, data13, data14, data15) VALUES (new.property_id, new.task_id, new.mimetype, new.prop_version, new.prop_sync1, new.prop_sync2, new.prop_sync3, new.prop_sync4, new.prop_sync5, new.prop_sync6, new.prop_sync7, new.prop_sync8, new.data0, new.data1, new.data2, new.data3, new.data4, new.data5, new.data6, new.data7, new.data8, new.data9, new.data10, new.data11, new.data12, new.data13, new.data14, new.data15);\n"
                   +
                   "END;");
        db.execSQL("Create TRIGGER caldav_property_update_tag_trigger INSTEAD OF UPDATE ON caldav_property_view\n"
                   +
                   "WHEN new.mimetype = 'vnd.android.cursor.item/category'\n" +
                   "BEGIN\n" +
                   "\tUPDATE tag SET name=new.data1, color=new.data2 WHERE _id=new.data0;\n" +
                   "\tINSERT INTO task_tag(tag_id,task_id) SELECT new.data0, new.task_id WHERE NOT EXISTS(SELECT 1 FROM task_tag WHERE task_tag.tag_id=new.data0 AND task_tag.task_id =new.task_id);\n"
                   +
                   "END;");
        db.execSQL("Create TRIGGER caldav_property_delete_tag_trigger INSTEAD OF DELETE ON caldav_property_view\n"
                   +
                   "WHEN new.mimetype = 'vnd.android.cursor.item/category'\n" +
                   "BEGIN\n" +
                   "\tDELETE FROM tag WHERE _id=old.data0;\n" +
                   "END;");
    }

    private static void createCaldavCategories(final SQLiteDatabase db) {
        // View
        // This is just a cross product of the tag table and all possible accounts.
        // I think we need this because the caldav task adapter could do something like
        // SELECT * FROM caldav_categories WHERE account_name="…";
        // And we have one list of tags for all accounts
        db.execSQL("CREATE VIEW caldav_categories AS\n" +
                   "SELECT _id, account_name, account_type, name, color FROM tag,\n" +
                   "(SELECT DISTINCT(account_name) account_name, account_type FROM caldav_lists) as account_info;");
        // INSERT Trigger
        db.execSQL("Create TRIGGER caldav_categories_insert_trigger INSTEAD OF INSERT ON caldav_categories\n"
                   +
                   "BEGIN\n" +
                   "    INSERT OR REPLACE INTO tag (name,color) VALUES (new.name, new.color);\n" +
                   "END;");
        // UPDATE Trigger
        db.execSQL("CREATE TRIGGER caldav_categories_update_trigger INSTEAD OF UPDATE ON caldav_categories\n"
                   +
                   "BEGIN\n" +
                   "    UPDATE tag SET name=new.name, color=new.color WHERE _id=new._id;\n" +
                   "END;");
        // DELETE Trigger
        // Do nothing! We care about tags not caldav!
        db.execSQL("CREATE TRIGGER caldav_categories_delete_trigger INSTEAD OF DELETE ON caldav_categories\n"
                   +
                   "BEGIN\n" +
                   "SELECT _id from tag;\n" +
                   "END;");
    }

    private static void createCaldavAlarms(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE caldav_alarms (\n" +
                   "alarm_id INTEGER PRIMARY KEY,\n" +
                   "last_trigger TEXT,\n" +
                   "next_trigger TEXT);");
    }
    private static void updateCaldavExtra(final SQLiteDatabase db) {
        createCaldavLists(db);
        createCaldavTasks(db);
        createCaldavProperties(db);
        createCaldavCategories(db);
        createCaldavAlarms(db);
        db.execSQL("DROP TABLE caldav_extra;");
    }

    private static void createTableRecurrenceTW(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE "
                   + Recurring.TW_TABLE
                   + '('
                   + ModelBase.ID
                   + " INTEGER PRIMARY KEY,"
                   + "parent INTEGER REFERENCES "
                   + Task.TABLE
                   + " ("
                   + ModelBase.ID
                   + ") "
                   + "ON DELETE CASCADE ON UPDATE CASCADE,child INTEGER REFERENCES "
                   + Task.TABLE
                   + " ("
                   + ModelBase.ID
                   + ") "
                   + "ON DELETE CASCADE ON UPDATE CASCADE ,offset INTEGER,offsetCount INTEGER)");
    }

    private static void createCalDavExtraTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE caldav_extra(" + ModelBase.ID + " INTEGER PRIMARY KEY,"
                   + "ETAG TEXT," + "SYNC_ID TEXT DEFAULT NULL, "
                   + "REMOTE_NAME TEXT)");
    }

    private static void createFileTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + FileMirakel.TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT, " + "task" + ModelBase.ID
                   + " INTEGER NOT NULL DEFAULT 0, " + "name TEXT, " + "path TEXT"
                   + ')');
    }

    private static void createSubtaskTable(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Task.SUBTASK_TABLE + " (" + ModelBase.ID
                   + " INTEGER PRIMARY KEY AUTOINCREMENT," + "parent" + ModelBase.ID
                   + " INTEGER REFERENCES " + Task.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE," + "child" + ModelBase.ID
                   + " INTEGER REFERENCES " + Task.TABLE + " (" + ModelBase.ID
                   + ") ON DELETE CASCADE ON UPDATE CASCADE);");
    }

    private static void updateTimesToUTC(final SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + Task.TABLE + " RENAME TO tmp_tasks;");
        createTasksTable(db);
        final int offset = DateTimeHelper.getTimeZoneOffset(false,
                           new GregorianCalendar());
        db.execSQL("Insert INTO tasks (_id, uuid, list_id, name, "
                   + "content, done, due, reminder, priority, created_at, "
                   + "updated_at, sync_state, additional_entries, recurring, "
                   + "recurring_reminder, progress) "
                   + "Select _id, uuid, list_id, name, content, done, "
                   + "strftime('%s',"
                   + Task.DUE
                   + ") - ("
                   + offset
                   + "), "
                   + getStrFtime(Task.REMINDER, offset)
                   + ", priority, "
                   + getStrFtime(CREATED_AT, offset)
                   + ", "
                   + getStrFtime(UPDATED_AT, offset)
                   + ", "
                   + "sync_state, additional_entries, recurring, recurring_reminder, progress FROM tmp_tasks;");
        db.execSQL("DROP TABLE tmp_tasks");
    }

    private static String getStrFtime(final String col, final int offset) {
        String ret = "strftime('%s',substr(" + col + ",0,11)||' '||substr("
                     + col + ",12,2)||':'||substr(" + col + ",14,2)||':'||substr("
                     + col + ",16,2)) - (" + offset + ")";
        if (col.equals(CREATED_AT) || col.equals(UPDATED_AT)) {
            ret = "CASE WHEN (" + ret
                  + ") IS NULL THEN strftime('%s','now') ELSE (" + ret
                  + ") END";
        }
        return ret;
    }
}
