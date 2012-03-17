/*
 * The author disclaims copyright to this source code.  In place of
 * a legal notice, here is a blessing:
 *
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package org.sqlite;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

public class Conn extends AbstractConn {
  public static final String MEMORY = ":memory:";
  public static final String TEMP_FILE = "";

  private final String filename;
  private final boolean readonly;
  private Pointer pDb;

  /**
   * @param filename ":memory:" for memory db, "" for temp file db
   * @param flags    org.sqlite.OpenFlags.* (TODO EnumSet or BitSet, default flags)
   * @param vfs      may be null
   * @return Opened Connection
   * @throws SQLiteException
   */
  public static Conn open(String filename, int flags, String vfs) throws SQLiteException {
    if (!SQLite.sqlite3_threadsafe()) {
      throw new SQLiteException("sqlite library was not compiled for thread-safe operation", ErrCodes.WRAPPER_SPECIFIC);
    }
    final PointerByReference ppDb = new PointerByReference();
    final int res = SQLite.sqlite3_open_v2(filename, ppDb, flags, vfs);
    if (res != SQLite.SQLITE_OK) {
      if (ppDb.getValue() != null) {
        SQLite.sqlite3_close(ppDb.getValue());
      }
      throw new SQLiteException(String.format("error while opening a database connexion to '%s'", filename), res);
    }
    final boolean readonly = (flags & OpenFlags.SQLITE_OPEN_READONLY) != 0;
    return new Conn(filename, readonly, ppDb.getValue());
  }

  /**
   * @return result code (No exception is thrown).
   */
  @Override
  public int _close() {
    if (pDb == null) {
      return SQLite.SQLITE_OK;
    }

    // Dangling statements
    Pointer pStmt = SQLite.sqlite3_next_stmt(pDb, null);
    while (pStmt != null) {
      Util.trace("Dangling statement: " + SQLite.sqlite3_sql(pStmt));
      SQLite.sqlite3_finalize(pStmt);
      pStmt = SQLite.sqlite3_next_stmt(pDb, pStmt);
    }

    final int res = SQLite.sqlite3_close(pDb);
    if (res == SQLite.SQLITE_OK) {
      pDb = null;
    }
    return res;
  }

  private Conn(String filename, boolean readonly, Pointer pDb) {
    this.filename = filename;
    this.readonly = readonly;
    this.pDb = pDb;
  }

  @Override
  boolean readOnly() {
    return readonly;
  }

  /**
   * @param sql query
   * @return Prepared Statement
   * @throws ConnException
   */
  @Override
  public Stmt prepare(String sql) throws ConnException {
    checkOpen();
    final Pointer pSql = SQLite.nativeString(sql);
    final PointerByReference ppStmt = new PointerByReference();
    final PointerByReference ppTail = new PointerByReference();
    final int res = SQLite.sqlite3_prepare_v2(pDb, pSql, -1, ppStmt, ppTail);
    check(res, "error while preparing statement '%s'", sql);
    return new Stmt(this, ppStmt.getValue(), ppTail.getValue());
  }

  /**
   * @return Run-time library version number
   */
  @Override
  public String libversion() {
    return SQLite.sqlite3_libversion();
  }

  @Override
  String mprintf(String format, String arg) {
    return SQLite.sqlite3_mprintf(format, arg);
  }

  @Override
  Stmt create() {
    return new Stmt(this);
  }

  @Override
  public void exec(String sql) throws ConnException, StmtException {
    while (sql != null && sql.length() > 0) {
      Stmt s = null;
      try {
        s = prepare(sql);
        sql = s.getTail();
        if (!s.isDumb()) { // this happens for a comment or white-space
          s.exec();
        }
      } finally {
        if (s != null) {
          s.close();
        }
      }
    }
  }

  /**
   * @return the number of database rows that were changed or inserted or deleted by the most recently completed SQL statement
   *         on the database connection specified by the first parameter.
   * @throws ConnException
   */
  public int getChanges() throws ConnException {
    checkOpen();
    return SQLite.sqlite3_changes(pDb);
  }
  /**
   * @return Total number of rows modified
   * @throws ConnException
   */
  public int getTotalChanges() throws ConnException {
    checkOpen();
    return SQLite.sqlite3_total_changes(pDb);
  }

  /**
   * @return the rowid of the most recent successful INSERT into the database.
   */
  public long getLastInsertRowid() throws ConnException {
    checkOpen();
    return SQLite.sqlite3_last_insert_rowid(pDb);
  }

  /**
   * Interrupt a long-running query
   */
  public void interrupt() throws ConnException {
    checkOpen();
    SQLite.sqlite3_interrupt(pDb);
  }

  public void setBusyTimeout(int ms) throws ConnException {
    checkOpen();
    check(SQLite.sqlite3_busy_timeout(pDb, ms), "error while setting busy timeout on '%s'", filename);
  }

  @Override
  public String getFilename() {
    return filename;
  }

  public String getErrMsg() {
    return SQLite.sqlite3_errmsg(pDb);
  }

  /**
   * @return org.sqlite.ErrCodes.*
   */
  public int getErrCode() {
    return SQLite.sqlite3_errcode(pDb);
  }

  /**
   * @param onoff Enable Or Disable Extended Result Codes
   */
  public void setExtendedResultCodes(boolean onoff) throws ConnException {
    checkOpen();
    check(SQLite.sqlite3_extended_result_codes(pDb, onoff), "error while enabling extended result codes on '%s'", filename);
  }

  /**
   * @return org.sqlite.ExtErrCodes.*
   */
  public int getExtendedErrcode() {
    return SQLite.sqlite3_extended_errcode(pDb);
  }

  boolean[] getTableColumnMetadata(String dbName, String tblName, String colName) throws ConnException {
    final PointerByReference pNotNull = new PointerByReference();
    final PointerByReference pPrimaryKey = new PointerByReference();
    final PointerByReference pAutoinc = new PointerByReference();

    check(SQLite.sqlite3_table_column_metadata(pDb,
        dbName,
        tblName,
        colName,
        null, null,
        pNotNull, pPrimaryKey, pAutoinc), "error while accessing table column metatada of '%s'", tblName);

    return new boolean[]{toBool(pNotNull), toBool(pPrimaryKey), toBool(pAutoinc)};
  }
  private static boolean toBool(PointerByReference p) {
    return p.getPointer().getInt(0) > 0;
  }

  @Override
  public boolean isClosed() {
    return pDb == null;
  }
  @Override
  void checkOpen() throws ConnException {
    if (isClosed()) {
      throw new ConnException(this, String.format("connection to '%s' closed", filename), ErrCodes.WRAPPER_SPECIFIC);
    }
  }
  private void check(int res, String format, String param) throws ConnException {
    if (res != SQLite.SQLITE_OK) {
      throw new ConnException(this, String.format(format, param), res);
    }
  }
  @Override
  void check(int res, String reason) throws ConnException {
    if (res != SQLite.SQLITE_OK) {
      throw new ConnException(this, reason, res);
    }
  }
}
