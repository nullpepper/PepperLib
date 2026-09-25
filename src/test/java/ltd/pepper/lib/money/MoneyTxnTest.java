package ltd.pepper.lib.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * WP3（PepperClaim P1-2-1/2/11/12）：资金事务模板——
 * 扣款/进账结果码强制检查、业务失败自动回滚/冲正，杜绝「钱扣了东西没给」。
 */
class MoneyTxnTest {

    @Test
    void chargeFailureRejectsWithoutRunningBusiness() {
        final AtomicInteger businessRuns = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();

        assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.charged("teleport", () -> false, businessRuns::incrementAndGet, () -> {
                    refunds.incrementAndGet();
                    return true;
                }));
        assertEquals(0, businessRuns.get(), "扣款失败不得执行业务");
        assertEquals(0, refunds.get(), "扣款失败无需退款");
    }

    @Test
    void businessFailureTriggersRefundAndRethrows() {
        final AtomicInteger refunds = new AtomicInteger();
        final IllegalStateException boom = new IllegalStateException("boom");

        final MoneyTxnException failure = assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.charged(
                        "teleport",
                        () -> true,
                        () -> {
                            throw boom;
                        },
                        () -> {
                            refunds.incrementAndGet();
                            return true;
                        }));

        assertEquals(1, refunds.get(), "业务失败必须退款回滚");
        assertEquals(boom, failure.getCause());
    }

    @Test
    void refundFailureAfterBusinessFailureIsRecordedNotSwallowed() {
        final MoneyTxnException failure = assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.charged(
                        "teleport",
                        () -> true,
                        () -> {
                            throw new IllegalStateException("boom");
                        },
                        () -> false));

        assertTrue(
                failure.getSuppressed().length > 0 || failure.getMessage().contains("退款失败"),
                "退款失败必须留痕（suppressed 或消息），不得静默");
    }

    @Test
    void successRunsBusinessWithoutRefund() {
        final AtomicInteger refunds = new AtomicInteger();
        MoneyTxn.charged("teleport", () -> true, () -> {}, () -> {
            refunds.incrementAndGet();
            return true;
        });
        assertEquals(0, refunds.get());
    }

    @Test
    void creditFailureRejectsWithoutRunningBusiness() {
        final AtomicInteger businessRuns = new AtomicInteger();
        assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.credited("auction-payout", () -> false, businessRuns::incrementAndGet, () -> true));
        assertEquals(0, businessRuns.get());
    }

    @Test
    void businessFailureAfterCreditTriggersReversal() {
        final AtomicInteger reversals = new AtomicInteger();
        assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.credited(
                        "auction-payout",
                        () -> true,
                        () -> {
                            throw new IllegalStateException("boom");
                        },
                        () -> {
                            reversals.incrementAndGet();
                            return true;
                        }));
        assertEquals(1, reversals.get(), "进账后业务失败必须冲正");
    }

    @Test
    void requireSuccessTurnsFalseIntoException() {
        assertThrows(MoneyTxnException.class, () -> MoneyTxn.requireSuccess(() -> false, "deposit"));
        assertTrue(MoneyTxn.requireSuccess(() -> true, "deposit"));
    }

    @Test
    void failedRefundStillPropagatesOriginalBusinessFailure() {
        final AtomicInteger ran = new AtomicInteger();
        final MoneyTxnException failure = assertThrows(
                MoneyTxnException.class,
                () -> MoneyTxn.charged(
                        "x",
                        () -> true,
                        () -> {
                            ran.incrementAndGet();
                            throw new IllegalStateException("business-cause");
                        },
                        () -> false));
        assertFalse(failure.getMessage().isEmpty());
        assertEquals("business-cause", failure.getCause().getMessage());
    }
}
