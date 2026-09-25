package ltd.pepper.lib.money;

/**
 * 资金事务模板（PepperClaim P1-2-1/2/11/12 的统一防线）：
 *
 * <ul>
 *   <li><b>结果码强制检查</b>：资金操作返回 {@code false} 即抛
 *       {@link MoneyTxnException}，杜绝「忽略 depositPlayer 返回值」式静默吞失败；</li>
 *   <li><b>两阶段回滚</b>：{@link #charged} 扣款成功后业务失败 → 自动退款；
 *       {@link #credited} 进账成功后业务失败 → 自动冲正，杜绝「钱扣了东西没给」
 *       与「先删行后放款失败」的资金凭空消失；</li>
 *   <li><b>留痕</b>：业务失败为原始因（cause），退款/冲正失败以 suppressed 记录，
 *       两笔都不得静默。</li>
 * </ul>
 */
public final class MoneyTxn {

    /** 一笔资金操作（withdraw/deposit/refund 等）：{@code true} 成功。 */
    @FunctionalInterface
    public interface MoneyOp {
        boolean run();
    }

    private MoneyTxn() {}

    /** 结果码强制检查：{@code false} 抛 {@link MoneyTxnException}；成功返回 {@code true}。 */
    public static boolean requireSuccess(final MoneyOp op, final String operation) {
        final boolean ok;
        try {
            ok = op.run();
        } catch (final RuntimeException e) {
            throw new MoneyTxnException("资金操作失败：" + operation, e);
        }
        if (!ok) {
            throw new MoneyTxnException("资金操作失败：" + operation + "（返回 false）");
        }
        return true;
    }

    /**
     * 扣款 → 业务 →（业务失败时）退款回滚。
     *
     * @param operation 操作名（异常消息用）
     * @param charge 扣款操作（false/异常即失败，业务不执行）
     * @param business 业务动作
     * @param refund 退款回滚操作（仅业务失败时调用）
     * @throws MoneyTxnException 扣款失败、业务失败（退款失败另加 suppressed）
     */
    public static void charged(
            final String operation, final MoneyOp charge, final Runnable business, final MoneyOp refund) {
        requireSuccess(charge, operation + "：扣款");
        try {
            business.run();
        } catch (final RuntimeException businessFailure) {
            final MoneyTxnException wrapper = new MoneyTxnException("业务失败已回滚：" + operation, businessFailure);
            rollback(operation + "：退款", refund, wrapper);
            throw wrapper;
        }
    }

    /**
     * 进账 → 业务 →（业务失败时）冲正。
     *
     * @param operation 操作名（异常消息用）
     * @param credit 进账操作（false/异常即失败，业务不执行）
     * @param business 业务动作
     * @param reversal 冲正操作（仅业务失败时调用）
     * @throws MoneyTxnException 进账失败、业务失败（冲正失败另加 suppressed）
     */
    public static void credited(
            final String operation, final MoneyOp credit, final Runnable business, final MoneyOp reversal) {
        requireSuccess(credit, operation + "：进账");
        try {
            business.run();
        } catch (final RuntimeException businessFailure) {
            final MoneyTxnException wrapper = new MoneyTxnException("业务失败已冲正：" + operation, businessFailure);
            rollback(operation + "：冲正", reversal, wrapper);
            throw wrapper;
        }
    }

    /** 回滚/冲正失败不得吞：以 suppressed 挂到抛出的事务异常上。 */
    private static void rollback(final String operation, final MoneyOp rollbackOp, final MoneyTxnException target) {
        try {
            if (!rollbackOp.run()) {
                target.addSuppressed(new MoneyTxnException("退款失败（返回 false）：" + operation));
            }
        } catch (final RuntimeException rollbackFailure) {
            target.addSuppressed(new MoneyTxnException("退款失败（异常）：" + operation, rollbackFailure));
        }
    }
}
