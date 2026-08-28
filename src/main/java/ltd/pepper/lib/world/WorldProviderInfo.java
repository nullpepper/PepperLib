package ltd.pepper.lib.world;

/**
 * provider 诊断信息：provider 标识/版本/支持范围。范围字符串为人类可读声明
 * （如 {@code "Paper 1.20.6 - 1.21.x"} / {@code "com.infernalsuite.aswm:api:3.0.0"}），
 * 不用于程序分支（可用性由服务注册本身表达）。
 */
public record WorldProviderInfo(
        String providerId, String providerVersion, String supportedPaperRange, String supportedSlimeApiRange) {

    public WorldProviderInfo {
        java.util.Objects.requireNonNull(providerId, "providerId");
        java.util.Objects.requireNonNull(providerVersion, "providerVersion");
    }
}
