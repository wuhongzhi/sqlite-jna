/*
 * The author disclaims copyright to this source code.  In place of
 * a legal notice, here is a blessing:
 *
 *    May you do good and not evil.
 *    May you find forgiveness for yourself and forgive others.
 *    May you share freely, never taking more than you give.
 */
package org.sqlite.driver;

import org.sqlite.ErrCodes;
import org.sqlite.StmtException;
import org.sqlite.ZeroBlob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
//#if mvn.project.property.jdbc.specification.version >= "4.2"
import java.sql.SQLType;
//#endif
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/*
Blob Incremental I/O:
--------------------

* Initialization:

CREATE TABLE test (
-- pk   INTEGER PRIMARY KEY -- optional ROWID alias
-- ...
   data BLOB
-- ...
);
INSERT INTO test (data) VALUES (zeroblob(:blob_size));
SELECT last_insert_rowid();
-- or
UPDATE test SET data = zeroblob(:blob_size) WHERE rowid = :rowid;

* Read:

SELECT [rowid,] data FROM test [WHERE rowid = :rowid ...];
  stmt.setRowId(1, rowId); -- (1): optional if (2)
  ...
  ResultSet rs = stmt.getResultSet();

  Blob blob;
  while (rs.next()) {
    rs.getRowId(1); -- (2): mandatory if not (1)
    blob = rs.getBlob(2);
    // ...
  }
  blob.free();
  rs.close();

* Write:

UPDATE test SET data = :blob WHERE rowid = :rowid;
  smt.setRowId(2, rowId); -- mandatory, first
  smt.setBlob|setBinaryStream(1, ...);

 */
class PrepStmt extends Stmt implements ParameterMetaData, SQLitePreparedStatement {
	private RowId rowId;
	private Map<Integer, org.sqlite.Blob> blobByParamIndex = Collections.emptyMap();

	private boolean batching;
	private Object[] bindings;
	private boolean[] bound;
	private boolean boundChecked;
	private List<Object[]> batch; // list of bindings

	PrepStmt(Conn c, org.sqlite.Stmt stmt) {
		super(c, stmt);
	}

	@Override
	public ResultSet executeQuery() throws SQLException {
		final org.sqlite.Stmt stmt = getStmt();
		stmt.reset();
		if (!boundChecked) {
			checkParameters(stmt);
		}
		final boolean hasRow = step(false);
		if (!hasRow && stmt.getColumnCount() == 0) { // FIXME some pragma may return zero...
			if (stmt.isReadOnly()) {
				throw new StmtException(stmt, "query does not return a ResultSet", ErrCodes.WRAPPER_SPECIFIC);
			} else {
				throw new StmtException(stmt, "update statement", ErrCodes.WRAPPER_SPECIFIC);
			}
		}
		return new Rows(this, hasRow);
	}

	@Override
	public int executeUpdate() throws SQLException {
		final org.sqlite.Stmt stmt = getStmt();
		if (!boundChecked) {
			checkParameters(stmt);
		}
		step(true);
		return getConn().getChanges();
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		bindNull(parameterIndex);
	}
	@Override
	public void setNull(String parameterName, int sqlType) throws SQLException {
		setNull(getBindParameterIndex(parameterName), sqlType);
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		bindInt(parameterIndex, x ? 1 : 0);
	}
	@Override
	public void setBoolean(String parameterName, boolean x) throws SQLException {
		setBoolean(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		bindInt(parameterIndex, x);
	}
	@Override
	public void setByte(String parameterName, byte x) throws SQLException {
		setByte(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		bindInt(parameterIndex, x);
	}
	@Override
	public void setShort(String parameterName, short x) throws SQLException {
		setShort(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		bindInt(parameterIndex, x);
	}
	@Override
	public void setInt(String parameterName, int x) throws SQLException {
		setInt(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {
		bindLong(parameterIndex, x);
	}
	@Override
	public void setLong(String parameterName, long x) throws SQLException {
		setLong(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {
		bindDouble(parameterIndex, x);
	}
	@Override
	public void setFloat(String parameterName, float x) throws SQLException {
		setFloat(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {
		bindDouble(parameterIndex, x);
	}
	@Override
	public void setDouble(String parameterName, double x) throws SQLException {
		setDouble(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else {
			bindText(parameterIndex, x.toString());
		}
	}
	@Override
	public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
		setBigDecimal(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {
		bindText(parameterIndex, x);
	}
	@Override
	public void setString(String parameterName, String x) throws SQLException {
		setString(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else {
			bindBlob(parameterIndex, x);
		}
	}
	@Override
	public void setBytes(String parameterName, byte[] x) throws SQLException {
		setBytes(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {
		bindDate(parameterIndex, x, 0);
	}
	@Override
	public void setDate(String parameterName, Date x) throws SQLException {
		setDate(getBindParameterIndex(parameterName), x);
	}

	private void bindDate(int parameterIndex, java.util.Date x, int cfgIdx) throws SQLException {
		if (null == x) {
			bindNull(parameterIndex);
		} else {
			final String fmt = conn().dateTimeConfig[cfgIdx];
			if (fmt == null || DateUtil.UNIXEPOCH.equals(fmt)) {
				bindLong(parameterIndex, cfgIdx == 0 ? DateUtil.normalizeDate(x.getTime(), null) : x.getTime());
			} else if (DateUtil.JULIANDAY.equals(fmt)) {
				final long unixepoch = cfgIdx == 0 ? DateUtil.normalizeDate(x.getTime(), null) : x.getTime();
				bindDouble(parameterIndex, DateUtil.toJulianDay(unixepoch));
			} else {
				bindText(parameterIndex, DateUtil.formatDate(x, fmt, null));
			}
		}
	}

	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {
		bindDate(parameterIndex, x, 1);
	}
	@Override
	public void setTime(String parameterName, Time x) throws SQLException {
		setTime(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
		bindDate(parameterIndex, x, 2);
	}
	@Override
	public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
		setTimestamp(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setAsciiStream");
	}
	@Override
	public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
		setAsciiStream(getBindParameterIndex(parameterName), x, length);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setUnicodeStream");
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		}
		if (rowId == null) { // No streaming mode...
			// throw new SQLException("You must set the associated RowId before opening a Blob");
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			try {
				org.sqlite.Blob.copy(x, output, length);
			} catch (IOException e) {
				throw new SQLException("Error while reading binary stream", e);
			}
			setBytes(parameterIndex, output.toByteArray());
			return;
		}
		org.sqlite.Blob blob = blobByParamIndex.get(parameterIndex);
		if (blob == null || blob.isClosed()) {
			blob = getStmt().open(parameterIndex, RowIdImpl.getValue(rowId), true); // FIXME getColumnOriginName can be called only with SELECT...
			if (blob != null) {
				if (blobByParamIndex.isEmpty() && !(blobByParamIndex instanceof TreeMap)) {
					blobByParamIndex = new TreeMap<>();
				}
				blobByParamIndex.put(parameterIndex, blob);
			} else {
				throw new SQLException("No Blob!"); // TODO improve message
			}
		} else {
			blob.reopen(RowIdImpl.getValue(rowId));
		}

		// The data will be read from the stream as needed until end-of-file is reached.
		try {
			org.sqlite.Blob.copy(x, blob.getOutputStream(), length);
		} catch (IOException e) {
			throw new SQLException("Error while reading binary stream", e);
		} finally {
			blob.close();
		}
		bound[parameterIndex - 1] = true;
	}
	@Override
	public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
		setBinaryStream(getBindParameterIndex(parameterName), x, length);
	}

	@Override
	public void clearParameters() throws SQLException {
		getStmt().clearBindings();
		if (bindings != null) {
			Arrays.fill(bindings, null);
			Arrays.fill(bound, false);
			boundChecked = false;
			rowId = null;
		}
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		setObject(parameterIndex, x, targetSqlType, 0);
	}
	//#if mvn.project.property.jdbc.specification.version >= "4.2"
	@Override
	public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
		setObject(parameterIndex, x, targetSqlType, 0);
	}
	//#endif

	@Override
	public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
		setObject(getBindParameterIndex(parameterName), x, targetSqlType);
	}
	//#if mvn.project.property.jdbc.specification.version >= "4.2"
	@Override
	public void setObject(String parameterName, Object x, SQLType targetSqlType) throws SQLException {
		setObject(getBindParameterIndex(parameterName), x, targetSqlType);
	}
	//#endif

	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else if (x instanceof String) {
			setString(parameterIndex, (String) x);
		} else if (x instanceof Boolean) {
			setBoolean(parameterIndex, (Boolean) x);
		} else if (x instanceof Integer) {
			setInt(parameterIndex, (Integer) x);
		} else if (x instanceof Long) {
			setLong(parameterIndex, (Long) x);
		} else if (x instanceof Float) {
			setFloat(parameterIndex, (Float) x);
		} else if (x instanceof Double) {
			setDouble(parameterIndex, (Double) x);
		} else if (x instanceof Date) {
			setDate(parameterIndex, (Date) x);
		} else if (x instanceof Time) {
			setTime(parameterIndex, (Time) x);
		} else if (x instanceof Timestamp) {
			setTimestamp(parameterIndex, (Timestamp) x);
		} else if (x instanceof BigDecimal) {
			setBigDecimal(parameterIndex, (BigDecimal) x);
		} else if (x instanceof Byte) {
			setByte(parameterIndex, (Byte) x);
		} else if (x instanceof Short) {
			setShort(parameterIndex, (Short) x);
		} else if (x instanceof Character) {
			setString(parameterIndex, x.toString());
		} else if (x instanceof byte[]) {
			setBytes(parameterIndex, (byte[]) x);
		} else if (x instanceof ZeroBlob) {
			bindZeroBlob(parameterIndex, (ZeroBlob) x);
		} else if (x instanceof Blob) {
			setBlob(parameterIndex, (Blob) x);
		} else if (x instanceof Clob) {
			setClob(parameterIndex, (Clob) x);
		} else if (x instanceof Array) {
			setArray(parameterIndex, (Array) x);
		} else {
			throw new StmtException(getStmt(), String.format("Unsupported type: %s", x.getClass().getName()), ErrCodes.WRAPPER_SPECIFIC);
		}
	}
	@Override
	public void setObject(String parameterName, Object x) throws SQLException {
		setObject(getBindParameterIndex(parameterName), x);
	}

	@Override
	public boolean execute() throws SQLException {
		final org.sqlite.Stmt stmt = getStmt();
		stmt.reset(); // may be reset twice but I don't see how it can be avoided
		if (!boundChecked) {
			checkParameters(stmt);
		}
		return exec();
	}

	@Override
	public void addBatch() throws SQLException {
		if (!batching) {
			batching = true;
		}
		if (batch == null) {
			batch = new ArrayList<>();
		}
		if (!boundChecked) {
			checkParameters(getStmt());
		}
		if (bindings == null) { // parameterCount == 0
			batch.add(null);
		} else {
			batch.add(Arrays.copyOf(bindings, bindings.length));
		}
	}

	@Override
	public void clearBatch() throws SQLException {
		checkOpen();
		if (batch != null) {
			batch.clear();
		}
		batching = false;
	}

	@Override
	public int[] executeBatch() throws SQLException {
		final org.sqlite.Stmt stmt = getStmt();
		batching = false;
		if (batch == null) {
			return new int[0]; // FIXME
		}
		final int size = batch.size();
		SQLException cause = null;
		Object[] params;
		final int[] changes = new int[size];
		for (int i = 0; i < size; ++i) {
			try {
				params = batch.get(i);
				if (params != null) {
					for (int j = 0; j < params.length; j++) {
						stmt.bindByIndex(j + 1, params[j]);
					}
				}
				changes[i] = executeUpdate();
			} catch (SQLException e) {
				if (cause != null) {
					e.setNextException(cause);
				}
				cause = e;
				changes[i] = EXECUTE_FAILED;
			}
		}
		clearBatch();
		if (cause != null) {
			throw new BatchUpdateException("batch failed", changes, cause);
		}
		return changes;
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setCharacterStream");
	}
	@Override
	public void setCharacterStream(String parameterName, Reader reader, int length) throws SQLException {
		setCharacterStream(getBindParameterIndex(parameterName), reader, length);
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		throw Util.unsupported("PreparedStatement.setRef");
	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else {
			setBinaryStream(parameterIndex, x.getBinaryStream(), x.length());
		}
	}
	@Override
	public void setBlob(String parameterName, Blob x) throws SQLException {
		setBlob(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		throw Util.unsupported("PreparedStatement.setClob");
	}
	@Override
	public void setClob(String parameterName, Clob x) throws SQLException {
		setClob(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		throw Util.unsupported("PreparedStatement.setArray");
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		final org.sqlite.Stmt stmt = getStmt();
		if (stmt.getColumnCount() == 0) {
			return null;
		}
		return new RowsMeta(stmt);
	}

	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		if (x == null || cal == null) {
			setDate(parameterIndex, x);
			return;
		}
		throw Util.unsupported("*PreparedStatement.setDate"); // TODO
	}
	@Override
	public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
		setDate(getBindParameterIndex(parameterName), x, cal);
	}

	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
		if (x == null || cal == null) {
			setTime(parameterIndex, x);
			return;
		}
		throw Util.unsupported("*PreparedStatement.setTime"); // TODO
	}
	@Override
	public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
		setTime(getBindParameterIndex(parameterName), x, cal);
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		if (x == null || cal == null) {
			setTimestamp(parameterIndex, x);
		}
		throw Util.unsupported("*PreparedStatement.setTimestamp"); // TODO
	}
	@Override
	public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
		setTimestamp(getBindParameterIndex(parameterName), x, cal);
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		setNull(parameterIndex, sqlType);
	}
	@Override
	public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
		setNull(getBindParameterIndex(parameterName), sqlType, typeName);
	}

	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		throw Util.unsupported("PreparedStatement.setURL");
	}
	@Override
	public void setURL(String parameterName, URL val) throws SQLException {
		setURL(getBindParameterIndex(parameterName), val);
	}

	@Override
	public ParameterMetaData getParameterMetaData() {
		return this;
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else {
			rowId = x;
			bindLong(parameterIndex, RowIdImpl.getValue(x));
		}
	}
	@Override
	public void setRowId(String parameterName, RowId x) throws SQLException {
		setRowId(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setNString(int parameterIndex, String value) throws SQLException {
		setString(parameterIndex, value);
	}
	@Override
	public void setNString(String parameterName, String value) throws SQLException {
		setNString(getBindParameterIndex(parameterName), value);
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setNCharacterStream");
	}
	@Override
	public void setNCharacterStream(String parameterName, Reader value, long length) throws SQLException {
		setNCharacterStream(getBindParameterIndex(parameterName), value, length);
	}

	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		throw Util.unsupported("PreparedStatement.setNClob");
	}
	@Override
	public void setNClob(String parameterName, NClob value) throws SQLException {
		setNClob(getBindParameterIndex(parameterName), value);
	}

	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setClob");
	}
	@Override
	public void setClob(String parameterName, Reader reader, long length) throws SQLException {
		setClob(getBindParameterIndex(parameterName), reader, length);
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		setBinaryStream(parameterIndex, inputStream, length);
	}
	@Override
	public void setBlob(String parameterName, InputStream inputStream, long length) throws SQLException {
		setBlob(getBindParameterIndex(parameterName), inputStream, length);
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setNClob");
	}
	@Override
	public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
		setNClob(getBindParameterIndex(parameterName), reader, length);
	}

	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		if (xmlObject == null) {
			bindNull(parameterIndex);
		} else {
			bindText(parameterIndex, xmlObject.getString());
		}
	}
	@Override
	public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
		setSQLXML(getBindParameterIndex(parameterName), xmlObject);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		if (x == null || targetSqlType == Types.NULL) {
			bindNull(parameterIndex);
			return;
		}
		if (Types.VARCHAR == targetSqlType) {
			if (x instanceof java.util.Date) {
				setString(parameterIndex, DateUtil.formatDate((java.util.Date) x, scaleOrLength, null));
				return;
			} else if (x instanceof LocalDate) {
				setString(parameterIndex, x.toString());
				return;
			}
		} else if (Types.INTEGER == targetSqlType) {
			if (x instanceof Number) {
				setLong(parameterIndex, ((Number) x).longValue());
				return;
			} else if (x instanceof java.util.Date) {
				final long unixepoch = ((java.util.Date) x).getTime();
				setLong(parameterIndex, x instanceof Date ? DateUtil.normalizeDate(unixepoch, null) : unixepoch);
				return;
			} else if (x instanceof LocalDate) {
				setLong(parameterIndex, ((LocalDate) x).toEpochDay());
				return;
			} else if (x instanceof OffsetDateTime) {
				setLong(parameterIndex, ((OffsetDateTime) x).toEpochSecond());
				return;
			}
		} else if (Types.REAL == targetSqlType) {
			if (x instanceof Number) {
				setDouble(parameterIndex, ((Number) x).doubleValue());
				return;
			} else if (x instanceof java.util.Date) {
				final long unixepoch = ((java.util.Date) x).getTime();
				setDouble(parameterIndex, DateUtil.toJulianDay(x instanceof Date ? DateUtil.normalizeDate(unixepoch, null) : unixepoch));
				return;
			}
		}
		// no conversion (targetSqlTpe and scaleOrLength are ignored)
		setObject(parameterIndex, x);
	}
	//#if mvn.project.property.jdbc.specification.version >= "4.2"
	@Override
	public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
		setObject(parameterIndex, x, targetSqlType.getVendorTypeNumber(), scaleOrLength);
	}
	//#endif

	@Override
	public void setObject(String parameterName, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		setObject(getBindParameterIndex(parameterName), x, targetSqlType, scaleOrLength);
	}
	//#if mvn.project.property.jdbc.specification.version >= "4.2"
	@Override
	public void setObject(String parameterName, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
		setObject(getBindParameterIndex(parameterName), x, targetSqlType, scaleOrLength);
	}
	//#endif

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setAsciiStream");
	}
	@Override
	public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
		setAsciiStream(getBindParameterIndex(parameterName), x, length);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		if (x == null) {
			bindNull(parameterIndex);
		} else {
			setBinaryStream(parameterIndex, x, BlobImpl.checkLength(length));
		}
	}
	@Override
	public void setBinaryStream(String parameterName, InputStream x, long length) throws SQLException {
		setBinaryStream(getBindParameterIndex(parameterName), x, length);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
		throw Util.unsupported("PreparedStatement.setCharacterStream");
	}
	@Override
	public void setCharacterStream(String parameterName, Reader reader, long length) throws SQLException {
		setCharacterStream(getBindParameterIndex(parameterName), reader, length);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		throw Util.unsupported("PreparedStatement.setAsciiStream");
	}
	@Override
	public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
		setAsciiStream(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
		setBinaryStream(parameterIndex, x, Integer.MAX_VALUE);
	}
	@Override
	public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
		setBinaryStream(getBindParameterIndex(parameterName), x);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		throw Util.unsupported("PreparedStatement.setCharacterStream");
	}
	@Override
	public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
		setCharacterStream(getBindParameterIndex(parameterName), reader);
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		throw Util.unsupported("PreparedStatement.setNCharacterStream");
	}
	@Override
	public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
		setNCharacterStream(getBindParameterIndex(parameterName), value);
	}

	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		throw Util.unsupported("PreparedStatement.setClob");
	}
	@Override
	public void setClob(String parameterName, Reader reader) throws SQLException {
		setClob(getBindParameterIndex(parameterName), reader);
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		setBinaryStream(parameterIndex, inputStream);
	}
	@Override
	public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
		setBlob(getBindParameterIndex(parameterName), inputStream);
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		throw Util.unsupported("PreparedStatement.setNClob");
	}
	@Override
	public void setNClob(String parameterName, Reader reader) throws SQLException {
		setNClob(getBindParameterIndex(parameterName), reader);
	}

	@Override
	public int getParameterCount() throws SQLException {
		return getStmt().getBindParameterCount();
	}

	@Override
	public int isNullable(int param) {
		return parameterNullableUnknown;
	}

	@Override
	public boolean isSigned(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.isSigned");
	}

	@Override
	public int getPrecision(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.getPrecision");
	}

	@Override
	public int getScale(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.getScale");
	}

	@Override
	public int getParameterType(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.getParameterType");
	}

	@Override
	public String getParameterTypeName(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.getParameterTypeName");
	}

	@Override
	public String getParameterClassName(int param) throws SQLException {
		throw Util.unsupported("ParameterMetaData.getParameterClassName");
	}

	@Override
	public int getParameterMode(int param) {
		return parameterModeIn;
	}

	private void bindNull(int parameterIndex) throws SQLException {
		if (!batching) {
			getStmt().bindNull(parameterIndex);
		}
		bind(parameterIndex, null);
	}

	private void bindInt(int parameterIndex, int x) throws SQLException {
		if (!batching) {
			getStmt().bindInt(parameterIndex, x);
		}
		bind(parameterIndex, x);
	}

	private void bindLong(int parameterIndex, long x) throws SQLException {
		if (!batching) {
			getStmt().bindLong(parameterIndex, x);
		}
		bind(parameterIndex, x);
	}

	private void bindDouble(int parameterIndex, double x) throws SQLException {
		if (!batching) {
			getStmt().bindDouble(parameterIndex, x);
		}
		bind(parameterIndex, x);
	}

	private void bindText(int parameterIndex, String x) throws SQLException {
		if (!batching) {
			getStmt().bindText(parameterIndex, x);
		}
		bind(parameterIndex, x);
	}

	private void bindBlob(int parameterIndex, byte[] x) throws SQLException {
		if (!batching) {
			getStmt().bindBlob(parameterIndex, x);
		}
		bind(parameterIndex, x);
	}

	private void bindZeroBlob(int parameterIndex, ZeroBlob x) throws SQLException {
		if (!batching) {
			getStmt().bindZeroblob(parameterIndex, x.n);
		}
		bind(parameterIndex, x);
	}

	private void bind(int parameterIndex, Object x) throws SQLException {
		if (bindings == null) {
			bindings = new Object[getParameterCount()];
			bound = new boolean[bindings.length];
		}
		bindings[parameterIndex - 1] = x;
		bound[parameterIndex - 1] = true;
	}

	/*
	From JDBC Specification:
	A value must be provided for each parameter marker in the PreparedStatement object before it can be executed.
	The methods used to execute a PreparedStatement object (executeQuery, executeUpdate and execute) will throw an SQLException
	if a value is not supplied for a parameter marker.
	 */
	private void checkParameters(org.sqlite.Stmt stmt) throws SQLException {
		if (stmt.getBindParameterCount() == 0) {
			boundChecked = true;
			return;
		}
		if (bindings == null) {
			throw new StmtException(stmt, "a value must be provided for each parameter marker in the PreparedStatement object before it can be executed.", ErrCodes.WRAPPER_SPECIFIC);
		} else {
			for (boolean b : bound) {
				if (!b) {
					throw new StmtException(stmt, "a value must be provided for each parameter marker in the PreparedStatement object before it can be executed.", ErrCodes.WRAPPER_SPECIFIC);
				}
			}
			boundChecked = true;
		}
	}

	private int getBindParameterIndex(String parameterName) throws SQLException {
		return getStmt().getBindParameterIndex(parameterName);
	}
}
