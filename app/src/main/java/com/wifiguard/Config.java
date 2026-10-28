package com.wifiguard;

/** 编译期常量：更新源配置。改完重新打包即可。 */
public final class Config {

    /**
     * GitHub 仓库，格式：用户名/仓库名，例如 "zhangsan/wifi-guard"。
     * 仓库里有 Release 且 Release 里挂了 .apk 附件，App 才能发现更新。
     */
    public static final String GITHUB_REPO = "dongbudu/wifi-guard";

    /** 自动检查更新的最小间隔（24 小时）。 */
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private Config() {
    }

    public static boolean updateSourceReady() {
        return GITHUB_REPO != null
                && GITHUB_REPO.contains("/")
                && !GITHUB_REPO.startsWith("CHANGE_ME");
    }
}
