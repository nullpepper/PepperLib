package ltd.pepper.lib.money;

/**
 * 资金事务失败（扣款/进账/退款/冲正任一步失败，或业务动作失败需回滚）。
 * 携带原始业务原因（{@link #getCause()}），退款失败以 suppressed 留痕。
 */
public class MoneyTxnException extends RuntimeException {

    public MoneyTxnException(final String message) {
        super(message);
    }

    public MoneyTxnException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
