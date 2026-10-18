package com.laker.postman.service;

import com.laker.postman.ioc.Autowired;
import com.laker.postman.ioc.Component;
import com.laker.postman.ioc.PreDestroy;
import com.laker.postman.platform.update.model.UpdateInfo;
import com.laker.postman.service.update.AppUpdateCheckCoordinator;
import com.laker.postman.service.update.plugin.PluginUpdateCheckResult;
import com.laker.postman.service.update.plugin.PluginUpdateCheckCoordinator;
import com.laker.postman.util.I18nUtil;
import com.laker.postman.util.MessageKeys;
import com.laker.postman.common.component.notification.NotificationCenter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * 版本更新服务
 */
@Slf4j
@Component
public class UpdateService {

    /**
     * 定制版禁止应用本体更新：官方安装包会覆盖当前定制客户端。
     * 插件更新仍保留，只关闭主程序更新检查与安装入口。
     */
    public static final boolean APP_UPDATE_ENABLED = false;

    @Autowired
    private AppUpdateCheckCoordinator appUpdateCheckCoordinator;

    @Autowired
    private PluginUpdateCheckCoordinator pluginUpdateCheckCoordinator;

    /**
     * 启动时异步检查更新
     */
    public void checkUpdateOnStartup() {
        // 定制版不做应用本体更新；避免官方新版本提示和覆盖。
        if (APP_UPDATE_ENABLED) {
            appUpdateCheckCoordinator.startBackgroundCheck();
        }
        pluginUpdateCheckCoordinator.startBackgroundCheck();
    }

    /**
     * 手动检查更新（用于菜单调用）
     */
    public void checkUpdateManually() {
        if (!APP_UPDATE_ENABLED) {
            // 只检查插件，明确告知应用本体更新被定制版锁定。
            NotificationCenter.showInfo(I18nUtil.getMessage(MessageKeys.UPDATE_APP_UPDATE_DISABLED));
            pluginUpdateCheckCoordinator.checkForUpdateManually()
                    .thenAccept(pluginUpdateCheckCoordinator::handleManualCheckResult)
                    .exceptionally(throwable -> {
                        log.error("Manual plugin update check failed", throwable);
                        return null;
                    });
            return;
        }
        CompletableFuture<PluginUpdateCheckResult> pluginUpdateFuture = pluginUpdateCheckCoordinator.checkForUpdateManually();
        appUpdateCheckCoordinator.checkForUpdateManually()
                .thenCombine(pluginUpdateFuture, (updateInfo, pluginUpdateResult) -> {
                    handleManualCheckResult(updateInfo, pluginUpdateResult);
                    return null;
                })
                .exceptionally(throwable -> {
                    log.error("Manual update check failed", throwable);
                    return null;
                });
    }

    private void handleManualCheckResult(UpdateInfo updateInfo, PluginUpdateCheckResult pluginUpdateResult) {
        boolean appNoUpdate = updateInfo != null && updateInfo.getStatus() == UpdateInfo.Status.NO_UPDATE;
        boolean pluginCheckSucceeded = pluginUpdateResult != null && !pluginUpdateResult.failed();
        boolean pluginHasUpdates = pluginUpdateResult != null && pluginUpdateResult.hasUpdates();

        if (appNoUpdate && pluginCheckSucceeded && !pluginHasUpdates) {
            NotificationCenter.showInfo(I18nUtil.getMessage(MessageKeys.UPDATE_ALREADY_LATEST_WITH_PLUGINS));
            return;
        }

        if (!(appNoUpdate && pluginHasUpdates)) {
            appUpdateCheckCoordinator.handleUpdateCheckResult(updateInfo, true);
        }
        pluginUpdateCheckCoordinator.handleManualCheckResult(pluginUpdateResult);
    }

}
