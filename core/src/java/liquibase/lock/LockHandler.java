package liquibase.lock;

import liquibase.DatabaseChangeLogLock;
import liquibase.database.Database;
import liquibase.database.sql.RawSqlStatement;
import liquibase.database.sql.UpdateStatement;
import liquibase.exception.JDBCException;
import liquibase.exception.LockException;
import liquibase.log.LogFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.DateFormat;
import java.util.*;

public class LockHandler {

    private Database database;
    private boolean hasChangeLogLock = false;

    private long changeLogLockWaitTime = 1000 * 60 * 5;  //default to 5 mins

    private static Map<Database, LockHandler> instances = new HashMap<Database, LockHandler>();

    private LockHandler(Database database) {
        this.database = database;
    }

    public static LockHandler getInstance(Database database) {
        if (!instances.containsKey(database)) {
            instances.put(database, new LockHandler(database));
        }
        return instances.get(database);
    }

    public boolean acquireLock() throws LockException {
        if (!database.doesChangeLogLockTableExist()) {
            throw new LockException("Could not acquire lock, table does not exist");
        }

        try {
            Boolean locked;
            try {
                locked = (Boolean) database.getJdbcTemplate().queryForObject(database.getSelectChangeLogLockSQL(), Boolean.class);
            } catch (JDBCException e) {
                if (!database.getJdbcTemplate().executesStatements()) {
                    //expected
                    locked = false;
                } else {
                    throw new LockException("Error checking database lock status", e);
                }
            }
            if (locked) {
                return false;
            } else {
                UpdateStatement updateStatement = new UpdateStatement(database.getDefaultSchemaName(), database.getDatabaseChangeLogLockTableName());
                updateStatement.addNewColumnValue("LOCKED", true);
                updateStatement.addNewColumnValue("LOCKGRANTED", new Timestamp(new java.util.Date().getTime()));
                updateStatement.addNewColumnValue("LOCKEDBY", InetAddress.getLocalHost().getCanonicalHostName() + " (" + InetAddress.getLocalHost().getHostAddress() + ")");
                updateStatement.setWhereClause("ID  = 1");

                database.getJdbcTemplate().comment("Lock Database");
                int rowsUpdated = database.getJdbcTemplate().update(updateStatement);
                if (rowsUpdated != 1) {
                    if (!database.getJdbcTemplate().executesStatements()) {
                        //expected
                    } else {
                        throw new LockException("Did not update change log lock correctly");
                    }
                }
                database.commit();
                LogFactory.getLogger().info("Successfully acquired change log lock");

                hasChangeLogLock = true;
                return true;
            }
        } catch (Exception e) {
            throw new LockException(e);
        }

    }

    public void releaseLock() throws LockException {
        if (database.doesChangeLogLockTableExist()) {
            try {
                UpdateStatement releaseStatement = new UpdateStatement(database.getDefaultSchemaName(), database.getDatabaseChangeLogLockTableName());
                releaseStatement.addNewColumnValue("LOCKED", false);
                releaseStatement.addNewColumnValue("LOCKGRANTED", null);
                releaseStatement.addNewColumnValue("LOCKEDBY", null);
                releaseStatement.setWhereClause(" ID = 1");

                database.getJdbcTemplate().comment("Release Database Lock");                
                int updatedRows = database.getJdbcTemplate().update(releaseStatement);
                if (updatedRows != 1) {
                    if (database.getJdbcTemplate().executesStatements()) {
                        throw new LockException("Did not update change log lock correctly.\n\n" + releaseStatement + " updated " + updatedRows + " instead of the expected 1 row.");
                    }
                }
                database.commit();
                hasChangeLogLock = false;

                LogFactory.getLogger().info("Successfully released change log lock");
            } catch (Exception e) {
                throw new LockException(e);
            }
        }
    }

    public DatabaseChangeLogLock[] listLocks() throws LockException {
        if (!database.doesChangeLogLockTableExist()) {
            return new DatabaseChangeLogLock[0];
        }

        try {
            List<DatabaseChangeLogLock> allLocks = new ArrayList<DatabaseChangeLogLock>();
            RawSqlStatement sqlStatement = new RawSqlStatement((("SELECT ID, LOCKED, LOCKGRANTED, LOCKEDBY FROM " + database.getDatabaseChangeLogLockTableName()).toUpperCase()));
            List<Map> rows = database.getJdbcTemplate().queryForList(sqlStatement);
            for (Map columnMap : rows) {
                Boolean locked = (Boolean) columnMap.get("LOCKED");
                if (locked != null && locked) {
                    allLocks.add(new DatabaseChangeLogLock((Integer) columnMap.get("ID"), (Date) columnMap.get("LOCKGRANTED"), (String) columnMap.get("LOCKEDBY")));
                }
            }
            return allLocks.toArray(new DatabaseChangeLogLock[allLocks.size()]);
        } catch (Exception e) {
            throw new LockException(e);
        }
    }

    public void waitForLock() throws LockException {
        if (hasChangeLogLock) {
            return;
        }

        try {
            database.checkDatabaseChangeLogLockTable();

            boolean locked = false;
            long timeToGiveUp = new Date().getTime() + changeLogLockWaitTime;
            while (!locked && new Date().getTime() < timeToGiveUp) {
                locked = acquireLock();
                if (!locked) {
                    System.out.println("Waiting for changelog lock....");
                    try {
                        Thread.sleep(1000 * 10);
                    } catch (InterruptedException e) {
                        ;
                    }
                }
            }

            if (!locked) {
                DatabaseChangeLogLock[] locks = listLocks();
                String lockedBy;
                if (locks.length > 0) {
                    DatabaseChangeLogLock lock = locks[0];
                    lockedBy = lock.getLockedBy() + " since " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(lock.getLockGranted());
                } else {
                    lockedBy = "UNKNOWN";
                }
                throw new LockException("Could not acquire change log lock.  Currently locked by " + lockedBy);
            }
        } catch (JDBCException e) {
            if (!database.getJdbcTemplate().executesStatements()) {
                ; //nothing to do
            } else {
                throw new LockException(e);
            }
        }
    }

    /**
     * Releases whatever locks are on the database change log table
     */
    public void forceReleaseLock() throws LockException, JDBCException, IOException {
        database.checkDatabaseChangeLogLockTable();

        releaseLock();
    }

}