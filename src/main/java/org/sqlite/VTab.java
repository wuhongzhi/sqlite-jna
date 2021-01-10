package org.sqlite;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.ByReference;
import com.sun.jna.Structure.FieldOrder;

import static com.sun.jna.Pointer.NULL;
import static org.sqlite.SQLite.nativeString;
import static org.sqlite.SQLite.sqlite3_free;

/**
 * Implementations must have memory allocated manually because the lifetime should not be handled by the JVM GC
 * (I don't see where we can keep a reference to avoid instances from being GCed
 * and https://www.sqlite.org/cgi/src/file?name=ext/misc/vtshim.c&ci=tip is not bundled by default).
 * @see <a href="https://sqlite.org/c3ref/vtab.html">sqlite3_vtab</a>
 */
@FieldOrder({"pModule", "nRef", "zErrMsg"})
public abstract class VTab<T extends VTab<T, C>, C extends VTabCursor<T>> extends Structure implements ByReference { // ByReference (see VTabCursor.pVtab)
	/**
	 * The module for this virtual table
	 */
	public Pointer pModule; // const sqlite3_module *
	/**
	 * Number of open cursors
	 */
	public int nRef;
	/**
	 * Error message from sqlite3_mprintf()
	 */
	public Pointer zErrMsg; // char *
	/* Virtual table implementations will typically add additional fields */

	public void setErrMsg(String errMsg) {
		if (zErrMsg != NULL) {
			sqlite3_free(zErrMsg);
		}
		zErrMsg = nativeString(errMsg, SQLite::sqlite3_malloc);
	}

	protected VTab(Pointer p) {
		super(p);
	}

	/**
	 * @return {@link ErrCodes}
	 */
	protected abstract int bestIndex(IndexInfo info);
	protected abstract C open() throws SQLiteException;
	/**
	 * Overriding method must call the super implementation of the method.
	 */
	protected void disconnect() throws SQLiteException {
		SQLite.sqlite3_free(getPointer());
	}
	protected void destroy() throws SQLiteException {
		disconnect();
	}
}