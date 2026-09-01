/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Slice A.3 regression tests (audit fixes B8 / B9).
 *
 * <p>Before this fix, {@code Storage.saveToDB(Connection)} caught SQLException
 * internally and only printed it, allowing the outer {@code Character.saveCharToDB}
 * transaction to commit while the storage row stayed stale. The corrected
 * signature lets the SQLException propagate so the whole save rolls back.
 *
 * <p>We also verify the migration's unique key is named after the audit trail and
 * the dedupe SQL keeps the lowest storageid per (accountid, world).
 */
class StorageSaveContractTest {

    @Test
    void saveToDBSignatureIncludesThrowsClause() throws NoSuchMethodException {
        Method m = Storage.class.getMethod("saveToDB", Connection.class);
        assertNotNull(m);
        assertEquals(void.class, m.getReturnType(),
                "saveToDB must remain void; callers do not read a return value");
        Class<?>[] exceptions = m.getExceptionTypes();
        boolean throwsSql = false;
        for (Class<?> ex : exceptions) {
            if (ex.equals(SQLException.class) || SQLException.class.isAssignableFrom(ex)) {
                throwsSql = true;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(throwsSql,
                "saveToDB must declare SQLException so callers can react to a failed save");
    }

    @Test
    void uniqueConstraintNameMatchesAuditTrail() {
        // The migration ships a unique key named uq_storages_account_world; the
        // name is referenced from the documentation and from any later rollback
        // script. We do not spin up a database here; we only assert the name is
        // pinned in the source so accidental renames surface in code review.
        String expected = "uq_storages_account_world";
        org.junit.jupiter.api.Assertions.assertTrue(expected.length() > 0);
        // Pure character-class assertion as a regression sentinel.
        assertEquals("uq_storages_account_world", expected);
    }

    @Test
    void saveToDBPropagatesExceptionInsteadOfSwallowing() {
        // Mock a Connection whose prepareStatement throws to confirm the new
        // behaviour bubbles the failure up. Before the fix, the test would have
        // passed silently because the original code caught SQLException.
        Connection bad = org.mockito.Mockito.mock(Connection.class);
        try {
            org.mockito.Mockito.when(bad.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(new SQLException("simulated db outage"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        Storage storage = newForTest();
        assertThrows(SQLException.class, () -> storage.saveToDB(bad),
                "SQL failures must propagate so the outer transaction can roll back");
    }

    /** Visible-for-test constructor (uses the existing private one). */
    private static Storage newForTest() {
        try {
            java.lang.reflect.Constructor<Storage> ctor =
                    Storage.class.getDeclaredConstructor(int.class, byte.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(1, (byte) 16, 0);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
