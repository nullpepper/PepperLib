package ltd.pepper.lib.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TransactionManagerTest {

    private File dbFile;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        this.dbFile = File.createTempFile("pepperlib-tx-test-", ".db");
        final String jdbcUrl = "jdbc:sqlite:" + this.dbFile.getAbsolutePath();

        try (Connection initConn = DriverManager.getConnection(jdbcUrl);
                Statement s = initConn.createStatement()) {
            s.execute("CREATE TABLE test_acc (id INT PRIMARY KEY, balance INT)");
            s.execute("INSERT INTO test_acc VALUES (1, 100), (2, 200)");
        }

        this.dataSource = Mockito.mock(DataSource.class);
        Mockito.when(this.dataSource.getConnection()).thenAnswer(inv -> DriverManager.getConnection(jdbcUrl));
    }

    @AfterEach
    void tearDown() {
        if (this.dbFile != null && this.dbFile.exists()) {
            this.dbFile.delete();
        }
    }

    @Test
    void transactionCommitsSuccessfully() throws SQLException {
        final TransactionManager tx = new TransactionManager(this.dataSource);
        tx.runInTransaction(conn -> {
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE test_acc SET balance = balance - 50 WHERE id = 1");
                s.executeUpdate("UPDATE test_acc SET balance = balance + 50 WHERE id = 2");
            }
        });

        try (Connection c = this.dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs1 = s.executeQuery("SELECT balance FROM test_acc WHERE id = 1")) {
            rs1.next();
            assertEquals(50, rs1.getInt(1));
        }
    }

    @Test
    void transactionRollsBackOnFailure() throws SQLException {
        final TransactionManager tx = new TransactionManager(this.dataSource);
        assertThrows(StorageException.class, () -> {
            tx.runInTransaction(conn -> {
                try (Statement s = conn.createStatement()) {
                    s.executeUpdate("UPDATE test_acc SET balance = 999 WHERE id = 1");
                }
                throw new SQLException("Simulated network fail during step 2");
            });
        });

        // 验证第一步已被回滚，账户 1 的余额依旧为 100
        try (Connection c = this.dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT balance FROM test_acc WHERE id = 1")) {
            rs.next();
            assertEquals(100, rs.getInt(1), "事务失败后必须回滚先前操作");
        }
    }
}
