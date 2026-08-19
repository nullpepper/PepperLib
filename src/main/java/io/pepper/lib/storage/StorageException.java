package io.pepper.lib.storage;

/**
 * 存储层运行时异常：包装底层 SQL 失败（{@link java.sql.SQLException}）与
 * 存储已关闭等存储层错误。
 *
 * <p>同步存储 API 不声明受检异常；所有 JDBC 失败统一包装为本异常向上抛出，
 * 由服务层捕获并转换为失败结果。只代表 bug / 基础设施故障。</p>
 *
 * <p><b>lib 内部异常</b>：插件不应直接向玩家暴露本类型；插件边界应捕获并
 * 包装为自己的存储异常（PepperUnion/PepperClaim 均已在存储入口做此包装）。</p>
 */
public final class StorageException extends RuntimeException {

    public StorageException(final String message) {
        super(message);
    }

    public StorageException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
