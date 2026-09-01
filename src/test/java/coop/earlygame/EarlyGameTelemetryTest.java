/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EarlyGameTelemetryTest {

    @Test
    void acceptsOnlyPositiveAwardsReceivedAtLevelsOneThroughThirty() {
        assertNull(EarlyGameTelemetry.acceptedRecord(1, 0, 0, 100, 1));
        assertNull(EarlyGameTelemetry.acceptedRecord(1, 31, 100, 100, 1));
        assertNull(EarlyGameTelemetry.acceptedRecord(1, 10, 100, 100, 0));
        assertNull(EarlyGameTelemetry.acceptedRecord(1, 10, 100, 100, -1));

        EarlyGameTelemetry.ExpRecord levelOne =
                EarlyGameTelemetry.acceptedRecord(1, 1, 0, 100, 25);
        EarlyGameTelemetry.ExpRecord levelThirty =
                EarlyGameTelemetry.acceptedRecord(1, 30, 100, 100, 50);
        assertNotNull(levelOne);
        assertNotNull(levelThirty);
        assertEquals(1, levelOne.level());
        assertEquals(30, levelThirty.level());
        assertEquals("UNATTRIBUTED", levelOne.source());
    }

    @Test
    void successfulBatchCommitsExplicitTransaction() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);

        EarlyGameTelemetry.insertBatch(con, List.of(record()));

        verify(con).setAutoCommit(false);
        verify(ps).executeBatch();
        verify(con).commit();
        verify(con, never()).rollback();
    }

    @Test
    void failedBatchRollsBackAndDoesNotCommit() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenThrow(new SQLException("write failed"));

        assertThrows(SQLException.class,
                () -> EarlyGameTelemetry.insertBatch(con, List.of(record())));

        verify(con).setAutoCommit(false);
        verify(con).rollback();
        verify(con, never()).commit();
    }

    @Test
    void integrityFailureDropsOnlyTheInvalidRecord() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement combined = mock(PreparedStatement.class);
        PreparedStatement valid = mock(PreparedStatement.class);
        PreparedStatement invalid = mock(PreparedStatement.class);
        when(con.prepareStatement(anyString())).thenReturn(combined, valid, invalid);
        when(combined.executeBatch()).thenThrow(new SQLException("foreign key", "23000"));
        when(invalid.executeBatch()).thenThrow(new SQLException("foreign key", "23000"));

        int dropped = EarlyGameTelemetry.insertBatchPreservingValid(
                con, List.of(record(), new EarlyGameTelemetry.ExpRecord(
                        2, 10, 100, 100000000, 25, "UNATTRIBUTED")));

        assertEquals(1, dropped);
        verify(con).commit();
        verify(con, times(2)).rollback();
    }

    private static EarlyGameTelemetry.ExpRecord record() {
        return new EarlyGameTelemetry.ExpRecord(1, 10, 100, 100000000,
                25, "UNATTRIBUTED");
    }
}
