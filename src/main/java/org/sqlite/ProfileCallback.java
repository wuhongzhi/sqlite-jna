/*
 * The author disclaims copyright to this source code.  In place of
 * a legal notice, here is a blessing:
 *
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package org.sqlite;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/**
 * Profiling callback.
 * @see <a href="http://sqlite.org/c3ref/profile.html">sqlite3_profile</a>
 */
public abstract class ProfileCallback implements Callback {
	/**
	 * @param sql SQL statement text.
	 * @param ns time in nanoseconds
	 */
	@SuppressWarnings("unused")
	public void invoke(Pointer arg, String sql, long ns) {
		profile(sql, ns);
	}

	/**
	 * @param sql SQL statement text.
	 * @param ns time in nanoseconds
	 */
	protected abstract void profile(String sql, long ns);
}
